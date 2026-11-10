package com.barnali.api.security;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Installs the authenticated application user as a transaction-local PostgreSQL setting.
 * The setting is bound after Spring binds the transaction connection and uses that same
 * connection for every JdbcTemplate call in the transaction.
 */
public final class BarnaliJdbcTransactionManager extends JdbcTransactionManager {
    static final String SET_IDENTITY_SQL = "SELECT set_config('barnali.user_id', ?, true)";

    private final DataSource dataSource;

    public BarnaliJdbcTransactionManager(DataSource dataSource) {
        super(dataSource);
        this.dataSource = dataSource;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        Connection connection = null;
        try {
            Object resource = TransactionSynchronizationManager.getResource(dataSource);
            if (!(resource instanceof ConnectionHolder holder)) {
                throw new IllegalStateException("Spring did not bind a JDBC transaction connection.");
            }
            connection = holder.getConnection();
            try (PreparedStatement statement = connection.prepareStatement(SET_IDENTITY_SQL)) {
                String userId = DatabaseIdentityContext.authenticatedUserId().map(Object::toString).orElse("");
                statement.setString(1, userId);
                statement.execute();
            }
        } catch (SQLException | RuntimeException failure) {
            rollbackAndUnbindAfterIdentityFailure(connection, definition, failure);
            throw new CannotCreateTransactionException(
                    "Could not establish transaction-scoped database identity.", failure);
        } finally {
            // While the transaction is active this is a no-op for Spring's bound connection.
            // If cleanup already unbound it after a setup failure, the transaction manager
            // itself has returned the connection to the pool.
            if (connection != null && TransactionSynchronizationManager.hasResource(dataSource)) {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        }
    }

    private void rollbackAndUnbindAfterIdentityFailure(Connection connection, TransactionDefinition definition,
            Exception failure) {
        Object resource = TransactionSynchronizationManager.getResource(dataSource);
        if (!(resource instanceof ConnectionHolder holder)) return;
        if (TransactionSynchronizationManager.hasResource(dataSource)) {
            TransactionSynchronizationManager.unbindResource(dataSource);
        }
        Connection transactionConnection = connection == null ? holder.getConnection() : connection;
        try {
            if (!transactionConnection.getAutoCommit()) transactionConnection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
        try {
            DataSourceUtils.resetConnectionAfterTransaction(transactionConnection, null, definition.isReadOnly());
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        holder.clear();
        try {
            DataSourceUtils.releaseConnection(transactionConnection, dataSource);
        } catch (RuntimeException releaseFailure) {
            failure.addSuppressed(releaseFailure);
        }
    }

}
