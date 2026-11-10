package com.barnali.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barnali.api.auth.AppPrincipal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.mockito.InOrder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class BarnaliJdbcTransactionManagerTest {
    @AfterEach
    void clearThreadState() {
        SecurityContextHolder.clearContext();
        TransactionSynchronizationManager.clear();
    }

    @Test
    void bindsPrincipalOnTheSpringBoundConnectionBeforeTheTransactionBodyAndCommits() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticate(userId);
        Fixture fixture = new Fixture();

        new TransactionTemplate(fixture.manager).execute(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.getResource(fixture.dataSource)).isNotNull();
            new JdbcTemplate(fixture.dataSource).execute("SELECT protected_query");
            return null;
        });

        InOrder calls = inOrder(fixture.connection, fixture.statement);
        calls.verify(fixture.connection).setAutoCommit(false);
        calls.verify(fixture.connection).prepareStatement(BarnaliJdbcTransactionManager.SET_IDENTITY_SQL);
        calls.verify(fixture.statement).setString(1, userId.toString());
        calls.verify(fixture.statement).execute();
        verify(fixture.connection).createStatement();
        verify(fixture.protectedQuery).execute("SELECT protected_query");
        calls.verify(fixture.connection).commit();
        verify(fixture.connection).close();
    }

    @Test
    void usesTransactionLocalEmptyIdentityForAnonymousAndForAReusedPooledConnection() throws Exception {
        UUID firstUser = UUID.randomUUID();
        authenticate(firstUser);
        Fixture fixture = new Fixture();

        new TransactionTemplate(fixture.manager).execute(status -> null);
        SecurityContextHolder.clearContext();
        new TransactionTemplate(fixture.manager).execute(status -> null);

        org.mockito.ArgumentCaptor<String> identityValues = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(fixture.statement, org.mockito.Mockito.times(2)).setString(org.mockito.Mockito.eq(1), identityValues.capture());
        assertThat(identityValues.getAllValues()).containsExactly(firstUser.toString(), "");
        verify(fixture.connection, org.mockito.Mockito.times(2)).commit();
        verify(fixture.connection, org.mockito.Mockito.times(2)).close();
    }

    @Test
    void rollsBackWhenTheCallerMarksTheTransactionRollbackOnly() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticate(userId);
        Fixture fixture = new Fixture();

        new TransactionTemplate(fixture.manager).execute(status -> {
            status.setRollbackOnly();
            return null;
        });

        verify(fixture.statement).setString(1, userId.toString());
        verify(fixture.connection).rollback();
        verify(fixture.connection).close();
    }

    @Test
    void setupFailureRollsBackAndUnbindsTheConnection() throws Exception {
        authenticate(UUID.randomUUID());
        Fixture fixture = new Fixture();
        when(fixture.connection.getAutoCommit()).thenReturn(true, false);
        when(fixture.statement.execute()).thenThrow(new java.sql.SQLException("synthetic failure", "08006"));

        assertThatThrownBy(() -> new TransactionTemplate(fixture.manager).execute(status -> null))
                .isInstanceOf(CannotCreateTransactionException.class);

        verify(fixture.connection).rollback();
        verify(fixture.connection).close();
        assertThat(TransactionSynchronizationManager.hasResource(fixture.dataSource)).isFalse();
    }

    @Test
    void hikariPhysicalConnectionStartsWithAnEmptyIdentityBaseline() throws Exception {
        String configuration;
        try (var stream = new ClassPathResource("application.yml").getInputStream()) {
            configuration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(configuration).contains("set_config('barnali.user_id', '', false)");
        assertThat(configuration).contains("set_config('statement_timeout', '5s', false)");
    }

    private static void authenticate(UUID userId) {
        AppPrincipal principal = new AppPrincipal(userId, "member@example.invalid", "member", "Member", "", "ACTIVE", false, 0);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static final class Fixture {
        private final DataSource dataSource = mock(DataSource.class);
        private final Connection connection = mock(Connection.class);
        private final PreparedStatement statement = mock(PreparedStatement.class);
        private final Statement protectedQuery = mock(Statement.class);
        private final BarnaliJdbcTransactionManager manager = new BarnaliJdbcTransactionManager(dataSource);

        private Fixture() {
            try {
                when(dataSource.getConnection()).thenReturn(connection);
                when(connection.getAutoCommit()).thenReturn(true);
                when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
                when(connection.prepareStatement(anyString())).thenReturn(statement);
                when(connection.createStatement()).thenReturn(protectedQuery);
                when(statement.execute()).thenReturn(true);
                when(protectedQuery.execute(anyString())).thenReturn(true);
            } catch (java.sql.SQLException exception) {
                throw new AssertionError(exception);
            }
        }
    }
}
