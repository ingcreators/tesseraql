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

    @Test
    void aMarkedNameGetsAStandIn() {
        // wirePath rewrites what SqlIdentifiers.PLACEHOLDER matches, and nothing else. A name the
        // contract did not admit travelled to the router verbatim, where Vert.x refuses `:ग्राहक`
        // outright — the route fails the boot (docs/two-way-sql-parser.md decision 11).
        assertThat(WireNames.wirePath("/customers/{ग्राहक}")).isEqualTo("/customers/{p0}");
        assertThat(WireNames.of(List.of("ग्राहक"))).containsEntry("ग्राहक", "p0");
    }

    @Test
    void aDecomposedNameGetsAStandIn() {
        // The silent one. A decomposed name with an ASCII prefix MOUNTS: Vert.x truncates the
        // parameter to `Vie` and leaves the rest as literal path text, so the route answers a
        // different URL and the declared bind is never populated.
        String nfd = java.text.Normalizer.normalize("Việt", java.text.Normalizer.Form.NFD);

        assertThat(WireNames.wirePath("/customers/{" + nfd + "}")).isEqualTo("/customers/{p0}");
    }

    @Test
    void everyNameTheContractAdmitsIsCarriedByTheRouter() {
        // The defect lives in the seam between two rules: the contract decides what a name may be,
        // the router decides what it will carry, and wirePath is the only thing translating — and
        // it translates only what the contract matched. An invariant, so the next widening of the
        // contract cannot re-open the same hole in silence.
        for (String name : List.of("id", "order_id", "受注番号", "ग्राहक", "مُحَمَّد", "ยิ้ม",
                java.text.Normalizer.normalize("Việt", java.text.Normalizer.Form.NFD))) {
            assertThat(io.tesseraql.core.sql.SqlIdentifiers.isIdentifier(name)).as(name).isTrue();
            String wired = WireNames.wirePath("/x/{" + name + "}");
            String carried = wired.substring(wired.indexOf('{') + 1, wired.indexOf('}'));
            assertThat(carried)
                    .as("the router carries only [A-Za-z][A-Za-z0-9]*, and got '" + carried + "'")
                    .matches("[A-Za-z][A-Za-z0-9]*");
        }
    }
}
