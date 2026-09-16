package io.tesseraql.core.expr;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a dotted path reaches (docs/audit-low-leads.md G12, G15): a map's key before its virtual
 * property, a record's own accessors, a bean getter, a public instance field — and nothing else.
 */
class EvaluationContextTest {

    /** The shape of {@code Principal}: components plus a hand-written accessor. */
    record Subject(String subject, Map<String, Object> claims) {
        public Map<String, Object> claim() {
            return claims;
        }

        public static Subject anonymous() {
            return new Subject("anonymous", Map.of());
        }
    }

    /** A plain class with a getter, a public field and a public static field. */
    static final class Bean {
        public static final String KIND = "bean";
        public final String label = "field";

        public int getCount() {
            return 3;
        }

        public boolean isReady() {
            return true;
        }

        public String strip() {
            return "invoked";
        }
    }

    private static Object resolve(String path, Map<String, Object> root) {
        return new EvaluationContext(root).resolve(List.of(path.split("\\.")));
    }

    @Test
    void aPresentMapKeyWinsOverTheVirtualProperty() {
        // Inputs named size, length and empty are ordinary (apparel, files, parts); the virtual
        // answer used to shadow them on every nested scope, so params.size was the number of
        // bound inputs and the input itself was unreachable.
        Map<String, Object> params = new HashMap<>();
        params.put("size", "L");
        params.put("length", 42);
        params.put("empty", "no");
        params.put("color", "red");
        Map<String, Object> root = Map.of("params", params, "row", Map.of("id", 1, "size", "XL"));
        assertThat(resolve("params.size", root)).isEqualTo("L");
        assertThat(resolve("params.length", root)).isEqualTo(42);
        assertThat(resolve("params.empty", root)).isEqualTo("no");
        assertThat(resolve("row.size", root)).isEqualTo("XL");
        // A key explicitly bound to null is the author's null, not the count.
        params.put("size", null);
        assertThat(resolve("params.size", root)).isNull();
    }

    @Test
    void theVirtualPropertyStillAnswersForAMapWithoutTheKey() {
        Map<String, Object> root = Map.of("params", Map.of("color", "red", "fit", "slim"),
                "lines", List.of(1, 2, 3), "ids", List.of());
        assertThat(resolve("params.size", root)).isEqualTo(2);
        assertThat(resolve("params.length", root)).isEqualTo(2);
        assertThat(resolve("params.empty", root)).isEqualTo(false);
        assertThat(resolve("lines.size", root)).isEqualTo(3);
        assertThat(resolve("ids.empty", root)).isEqualTo(true);
        // The absent-value answers (#1190) are untouched.
        assertThat(resolve("missing.empty", root)).isEqualTo(true);
        assertThat(resolve("missing.size", root)).isEqualTo(0);
    }

    @Test
    void aRecordAnswersItsOwnAccessorsByBareName() {
        Subject principal = new Subject("sub-1", Map.of("email", "a@example.com"));
        Map<String, Object> root = Map.of("principal", principal);
        assertThat(resolve("principal.subject", root)).isEqualTo("sub-1");
        // The hand-written accessor is how principal.claim.<name> resolves.
        assertThat(resolve("principal.claim.email", root)).isEqualTo("a@example.com");
        assertThat(resolve("principal.claims.email", root)).isEqualTo("a@example.com");
    }

    @Test
    void aBeanAnswersItsGettersAndInstanceFields() {
        Map<String, Object> root = Map.of("bean", new Bean(), "d", LocalDate.of(2026, 9, 16));
        assertThat(resolve("bean.count", root)).isEqualTo(3);
        assertThat(resolve("bean.ready", root)).isEqualTo(true);
        assertThat(resolve("bean.label", root)).isEqualTo("field");
        assertThat(resolve("d.year", root)).isEqualTo(2026);
        // A static field is not the value's property.
        assertThat(resolve("bean.KIND", root)).isNull();
    }

    @Test
    void nothingElseIsInvoked() {
        // The bare name used to be tried on every class: String.strip ran, Object.toString
        // echoed a principal's claims, getClass opened Class and its protection domain, and a
        // public static match (LocalDate.now) escaped as an uncaught IllegalArgumentException.
        Subject principal = new Subject("sub-1", Map.of("secret_claim", "s3cr3t"));
        Map<String, Object> root = Map.of("s", "hello", "list", List.of(1), "bean", new Bean(),
                "principal", principal, "d", LocalDate.of(2026, 9, 16));
        assertThat(resolve("s.strip", root)).isNull();
        assertThat(resolve("bean.strip", root)).isNull();
        assertThat(resolve("s.hashCode", root)).isNull();
        assertThat(resolve("s.toString", root)).isNull();
        assertThat(resolve("s.class", root)).isNull();
        assertThat(resolve("s.class.protectionDomain", root)).isNull();
        assertThat(resolve("list.stream", root)).isNull();
        assertThat(resolve("principal.toString", root)).isNull();
        assertThat(resolve("principal.hashCode", root)).isNull();
        assertThat(resolve("principal.class", root)).isNull();
        assertThat(resolve("principal.anonymous", root)).isNull();
        assertThat(resolve("d.now", root)).isNull();
        assertThat(resolve("d.class.module", root)).isNull();
    }

    @Test
    void theParserReachesTheSameAnswers() {
        Map<String, Object> root = Map.of("params", Map.of("size", "L"), "s", "hello",
                "d", LocalDate.of(2026, 9, 16));
        EvaluationContext context = new EvaluationContext(root);
        assertThat(ExpressionParser.parse("params.size == 'L'").evalBoolean(context)).isTrue();
        assertThat(ExpressionParser.parse("s.strip == 'hello'").evalBoolean(context)).isFalse();
        assertThat(ExpressionParser.parse("d.now != null").evalBoolean(context)).isFalse();
        assertThat(ExpressionParser.parse("d.year == 2026").evalBoolean(context)).isTrue();
    }
}
