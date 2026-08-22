package io.tesseraql.security.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The policy that resolves its atom from the request's own path
 * (docs/access-governance.md structural decision 7).
 *
 * <p>The value arrives as the router's own match now (docs/vertx-native.md decision 2) — the
 * re-parse that used to align the URI string against the template, strip its query and decode
 * its segments moved to the transport that had already done it. What stays this class's, and
 * this test's, is the atom grammar: whatever the URL carried, the resolved value must be one
 * plain segment, because anything else forges a different grant than the one addressed.
 */
class PolicyTemplateTest {

    private static String resolve(String name) {
        return PolicyTemplate.resolve("tql.iam.write.{name}",
                name == null ? Map.of() : Map.of("name", name));
    }

    @Test
    void aFixedPolicyIdIsNotATemplate() {
        assertThat(PolicyTemplate.isTemplate("tql.iam.admin.write")).isFalse();
        assertThat(PolicyTemplate.isTemplate(null)).isFalse();
        assertThat(PolicyTemplate.isTemplate("tql.iam.write.{name}")).isTrue();
    }

    @Test
    void theAtomIsResolvedFromTheRequestsPathParameter() {
        assertThat(resolve("orders")).isEqualTo("tql.iam.write.orders");
    }

    /** A non-ASCII application name is one segment like any other. */
    @Test
    void aNonAsciiSegmentResolves() {
        assertThat(resolve("受注")).isEqualTo("tql.iam.write.受注");
    }

    /** A request that carries no value for the placeholder resolves to nothing at all. */
    @Test
    void aMissingParameterResolvesToNothing() {
        assertThat(resolve(null)).isNull();
        assertThat(PolicyTemplate.resolve("tql.iam.write.{name}", Map.of("other", "x"))).isNull();
    }

    /**
     * An asterisk would otherwise resolve to the family's terminal wildcard — the grant that
     * delegates every application — so a request could ask to be checked against the broadest
     * atom in the family instead of the one it addressed.
     */
    @Test
    void anAsteriskNeverResolvesToTheWildcardAtom() {
        assertThat(resolve("*")).isNull();
    }

    /** A dotted value would forge a neighbouring atom out of the segment it was given. */
    @Test
    void aDottedValueCannotClimbTheAtomGrammar() {
        assertThat(resolve("orders.admin")).isNull();
    }

    /** A separator the URL smuggled in percent-encoded arrives decoded, and is refused here. */
    @Test
    void aSlashInTheDecodedValueIsRefused() {
        assertThat(resolve("a/b")).isNull();
    }

    @Test
    void anEmptyValueResolvesToNothing() {
        assertThat(resolve("")).isNull();
    }
}
