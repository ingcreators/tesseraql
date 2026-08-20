package io.tesseraql.core.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A route's URL template read against a request's path. */
class PathTemplateTest {

    @Test
    void aParameterTakesTheSegmentInItsPosition() {
        assertThat(PathTemplate.values("/users/{id}", "/users/u1"))
                .containsExactly(java.util.Map.entry("id", "u1"));
        assertThat(PathTemplate.values("/users/{id}/roles/{role}", "/users/u1/roles/admin"))
                .containsExactly(java.util.Map.entry("id", "u1"),
                        java.util.Map.entry("role", "admin"));
    }

    @Test
    void aQueryStringIsNotPartOfThePath() {
        assertThat(PathTemplate.values("/users/{id}", "/users/u1?id=u2"))
                .containsExactly(java.util.Map.entry("id", "u1"));
    }

    /**
     * The match aligns from the end, so an application served under a base path resolves the
     * same parameters as one served at the root (docs/base-path.md).
     */
    @Test
    void aBasePathPrefixIsIgnored() {
        assertThat(PathTemplate.values("/users/{id}", "/myapp/users/u1"))
                .containsExactly(java.util.Map.entry("id", "u1"));
    }

    /**
     * A literal segment that does not match answers nothing rather than a partial map: the
     * request did not come through this template, so nothing it carries is its parameter.
     */
    @Test
    void aPathThatDidNotComeThroughTheTemplateAnswersNothing() {
        assertThat(PathTemplate.values("/users/{id}/roles", "/users/u1/groups")).isEmpty();
        assertThat(PathTemplate.values("/users/{id}/roles", "/roles")).isEmpty();
        assertThat(PathTemplate.values("/users/{id}", null)).isEmpty();
        assertThat(PathTemplate.values(null, "/users/u1")).isEmpty();
    }

    @Test
    void aPercentEncodedSegmentIsDecodedOnce() {
        assertThat(PathTemplate.values("/受注/{受注番号}", "/受注/J-1001"))
                .containsExactly(java.util.Map.entry("受注番号", "J-1001"));
        assertThat(PathTemplate.values("/apps/{name}", "/apps/%E5%8F%97%E6%B3%A8"))
                .containsExactly(java.util.Map.entry("name", "受注"));
    }

    @Test
    void aTemplateWithNoParametersDeclaresNone() {
        assertThat(PathTemplate.parameterized("/users")).isFalse();
        assertThat(PathTemplate.parameterized("/users/{id}")).isTrue();
        assertThat(PathTemplate.parameterized(null)).isFalse();
        assertThat(PathTemplate.values("/users", "/users")).isEmpty();
    }
}
