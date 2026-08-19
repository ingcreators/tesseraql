package io.tesseraql.studio.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Studio edit gate: the {@code tql.studio.edit.<name>} atom, per
 * application and deny-by-default (docs/studio-shell.md structural decision 4).
 */
class StudioEditTest {

    @Test
    void theExactAtomEdits() {
        StudioEdit edit = new StudioEdit("orders", false);

        assertThat(edit.canEdit(List.of("tql.studio.edit.orders"))).isTrue();
        edit.requireEdit(List.of("tql.studio.edit.orders")); // does not throw
    }

    @Test
    void theFamilyWildcardEdits() {
        StudioEdit edit = new StudioEdit("orders", false);

        assertThat(edit.canEdit(List.of("tql.studio.edit.*"))).isTrue();
    }

    @Test
    void denyByDefault() {
        StudioEdit edit = new StudioEdit("orders", false);

        // No grant, no edit — there is no master switch to leave open.
        assertThat(edit.canEdit(List.of())).isFalse();
        assertThat(edit.canEdit(null)).isFalse();
        assertThat(edit.canEdit("tql.studio.edit.orders")).isFalse();
        // A neighbour's atom is a different authority: per application, which the retired
        // global editRoles allow-list never was.
        assertThat(edit.canEdit(List.of("tql.studio.edit.billing"))).isFalse();
        // Roles never open a framework surface.
        assertThat(edit.canEdit(List.of("ADMIN"))).isFalse();
        assertThatThrownBy(() -> edit.requireEdit(List.of("tql.studio.edit.billing")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("tql.studio.edit.orders");
    }

    @Test
    void confirmApplyOffNeverGatesAnApply() {
        StudioEdit edit = new StudioEdit("orders", false);

        assertThat(edit.confirmApply()).isFalse();
        edit.requireConfirm(false); // no gate -> no throw even without acknowledgment
    }

    @Test
    void confirmApplyOnRequiresAnAcknowledgment() {
        StudioEdit edit = new StudioEdit("orders", true);

        assertThat(edit.confirmApply()).isTrue();
        edit.requireConfirm(true); // acknowledged (confirm or force) -> ok
        assertThatThrownBy(() -> edit.requireConfirm(false))
                .isInstanceOf(TqlException.class).hasMessageContaining("confirm");
    }
}
