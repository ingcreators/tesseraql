package io.tesseraql.security.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The policy that resolves its atom from the request's own path
 * (docs/access-governance.md structural decision 7).
 */
class PolicyTemplateTest {

    private static final String TEMPLATE = "/_tesseraql/admin/applications/{name}/roles/assign";

    private static String resolve(String requestPath) {
        return PolicyTemplate.resolve("tql.iam.write.{name}", TEMPLATE, requestPath);
    }

    @Test
    void aFixedPolicyIdIsNotATemplate() {
        assertThat(PolicyTemplate.isTemplate("tql.iam.admin.write")).isFalse();
        assertThat(PolicyTemplate.isTemplate(null)).isFalse();
        assertThat(PolicyTemplate.isTemplate("tql.iam.write.{name}")).isTrue();
    }

    @Test
    void theAtomIsResolvedFromTheRequestsPath() {
        assertThat(resolve("/_tesseraql/admin/applications/orders/roles/assign"))
                .isEqualTo("tql.iam.write.orders");
    }

    /** A query string is not part of the path, and must not shift the segment alignment. */
    @Test
    void aQueryStringIsIgnored() {
        assertThat(resolve("/_tesseraql/admin/applications/orders/roles/assign?x=1"))
                .isEqualTo("tql.iam.write.orders");
    }

    /**
     * The match aligns from the end, so a deployment served under a base path resolves the
     * same atom as one served at the root (docs/base-path.md).
     */
    @Test
    void aBasePathPrefixDoesNotShiftTheSegments() {
        assertThat(resolve("/myapp/_tesseraql/admin/applications/orders/roles/assign"))
                .isEqualTo("tql.iam.write.orders");
    }

    /** A non-ASCII application name is one segment like any other, decoded before it is read. */
    @Test
    void aPercentEncodedSegmentIsDecodedBeforeItIsChecked() {
        assertThat(PolicyTemplate.resolve("tql.iam.write.{name}",
                "/admin/applications/{name}", "/admin/applications/%E5%8F%97%E6%B3%A8"))
                .isEqualTo("tql.iam.write.受注");
        // …and an escaped separator is then caught rather than smuggled through opaque.
        assertThat(PolicyTemplate.resolve("tql.iam.write.{name}",
                "/admin/applications/{name}", "/admin/applications/a%2Fb")).isNull();
    }

    /**
     * A request that did not come through this template carries none of its parameters. The
     * literal segments are checked for exactly that reason.
     */
    @Test
    void aPathThatDoesNotMatchTheTemplateResolvesToNothing() {
        assertThat(resolve("/_tesseraql/admin/applications/orders/roles/unassign")).isNull();
        assertThat(resolve("/roles/assign")).isNull();
        assertThat(PolicyTemplate.resolve("tql.iam.write.{name}", null, "/a/b")).isNull();
        assertThat(PolicyTemplate.resolve("tql.iam.write.{name}", TEMPLATE, null)).isNull();
    }

    /**
     * An asterisk in the path would otherwise resolve to the family's terminal wildcard — the
     * grant that delegates every application — so a request could ask to be checked against
     * the broadest atom in the family instead of the one it addressed.
     */
    @Test
    void anAsteriskInThePathNeverResolvesToTheWildcardAtom() {
        assertThat(resolve("/_tesseraql/admin/applications/*/roles/assign")).isNull();
    }

    /** A dotted value would forge a neighbouring atom out of the segment it was given. */
    @Test
    void aDottedValueCannotClimbTheAtomGrammar() {
        assertThat(resolve("/_tesseraql/admin/applications/orders.admin/roles/assign")).isNull();
    }

    @Test
    void anEmptySegmentResolvesToNothing() {
        assertThat(resolve("/_tesseraql/admin/applications//roles/assign")).isNull();
    }
}
