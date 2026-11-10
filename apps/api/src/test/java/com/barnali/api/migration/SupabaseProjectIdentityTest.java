package com.barnali.api.migration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SupabaseProjectIdentityTest {
    @Test
    void acceptsUsernameScopedToIndependentExpectedProjectReference() {
        assertThatCode(() -> SupabaseProjectIdentity.requireUsernameMatches(
                "barnali_runtime.projectref1234567890", "projectref1234567890"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMismatchWithoutEchoingEitherReference() {
        assertThatThrownBy(() -> SupabaseProjectIdentity.requireUsernameMatches(
                "barnali_runtime.projectref1234567890", "differentref123456"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("projectref1234567890")
                .hasMessageNotContaining("differentref123456");
    }

    @Test
    void rejectsMalformedExpectedReferenceWithoutEchoingIt() {
        assertThatThrownBy(() -> SupabaseProjectIdentity.requireUsernameMatches(
                "barnali_runtime.projectref1234567890", "not a project reference"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("not a project reference");
    }
}
