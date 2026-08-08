package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Wire-safe path-parameter stand-ins (docs/unicode-identifiers.md): the router only carries
 * {@code [A-Za-z][A-Za-z0-9]*} parameter names, so anything else — Japanese, and even
 * {@code order_id} — travels as {@code p<position>} and maps back in the binder.
 */
class WireNamesTest {

    @Test
    void wireSafeNamesPassThrough() {
        assertThat(WireNames.wirePath("/orders/{id}")).isEqualTo("/orders/{id}");
        assertThat(WireNames.of(List.of("id"))).containsEntry("id", "id");
    }

    @Test
    void unsafeNamesGetPositionalStandIns() {
        assertThat(WireNames.wirePath("/受注/{受注番号}")).isEqualTo("/受注/{p0}");
        assertThat(WireNames.wirePath("/a/{order_id}/b/{受注番号}"))
                .isEqualTo("/a/{p0}/b/{p1}");
        assertThat(WireNames.of(List.of("order_id", "受注番号")))
                .containsEntry("order_id", "p0")
                .containsEntry("受注番号", "p1");
    }
}
