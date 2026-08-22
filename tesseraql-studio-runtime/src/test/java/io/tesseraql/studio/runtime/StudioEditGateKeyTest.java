package io.tesseraql.studio.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The gate's params overloads own the key resolution (docs/studio-shell.md): the caller's
 * identity rides a provider's params as {@code principalPermissions} where a route had to keep
 * {@code permissions} free for a data field of that name, else as {@code permissions} — and a
 * provider that passes the whole params map cannot pick a wrong spelling.
 */
class StudioEditGateKeyTest {

    private final StudioEdit edit = new StudioEdit("user-admin", false);

    @Test
    void thePrincipalSpellingWinsWhenPresent() {
        assertThat(edit.canEdit(Map.of(
                "principalPermissions", List.of("tql.studio.edit.user-admin"),
                "permissions", List.of()))).isTrue();
        // The demoting direction holds too: whatever rides the principal spelling is what the
        // gate judges - a non-list there never falls through to the other key.
        assertThat(edit.canEdit(Map.of(
                "principalPermissions", "not-a-list",
                "permissions", List.of("tql.studio.edit.user-admin")))).isFalse();
    }

    @Test
    void fallsBackToPermissionsWhenThePrincipalSpellingIsAbsent() {
        assertThat(edit.canEdit(Map.of(
                "permissions", List.of("tql.studio.edit.*")))).isTrue();
        assertThat(edit.canEdit(Map.of())).isFalse();
    }
}
