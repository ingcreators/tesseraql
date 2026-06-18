package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unit tests for the Studio edit-permission gate (Studio backlog D6). */
class StudioAccessTest {

    @Test
    void readOnlyStudioNeverEdits() {
        StudioAccess access = new StudioAccess(false, Set.of("ADMIN"));

        assertThat(access.canEdit(List.of("ADMIN"))).isFalse();
        assertThatThrownBy(() -> access.requireEdit(List.of("ADMIN")))
                .isInstanceOf(TqlException.class);
    }

    @Test
    void writableWithoutEditRolesAllowsAnyAuthenticatedCaller() {
        StudioAccess access = new StudioAccess(true, Set.of());

        assertThat(access.canEdit(List.of())).isTrue();
        assertThat(access.canEdit(List.of("anything"))).isTrue();
        assertThat(access.canEdit(null)).isTrue();
        access.requireEdit(List.of()); // does not throw
    }

    @Test
    void editRolesGateByRole() {
        StudioAccess access = new StudioAccess(true, Set.of("STUDIO_EDITOR", "ADMIN"));

        assertThat(access.canEdit(List.of("ADMIN"))).isTrue();
        assertThat(access.canEdit(List.of("USER", "STUDIO_EDITOR"))).isTrue();
        assertThat(access.canEdit(List.of("VIEWER"))).isFalse();
        // A missing or non-list roles binding is treated as no roles.
        assertThat(access.canEdit(null)).isFalse();
        assertThat(access.canEdit("ADMIN")).isFalse();
        assertThatThrownBy(() -> access.requireEdit(List.of("VIEWER")))
                .isInstanceOf(TqlException.class).hasMessageContaining("roles");
    }
}
