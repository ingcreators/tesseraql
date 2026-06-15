package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.security.Principal;
import io.tesseraql.security.SecurityConfig;
import io.tesseraql.security.policy.Policy;
import io.tesseraql.security.policy.PolicyEngine;
import io.tesseraql.yaml.model.ResponseSpec.FieldPolicy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FieldPolicyApplierTest {

    private static Map<String, Object> row() {
        return new java.util.LinkedHashMap<>(Map.of(
                "id", 1, "name", "sato", "email", "sato@example.com", "salary", 1000));
    }

    private static Object data() {
        return Map.of("data", List.of(row()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstRow(Object body) {
        return (Map<String, Object>) ((List<?>) ((Map<String, Object>) body).get("data")).get(0);
    }

    @Test
    void hidesInvisibleField() {
        FieldPolicyApplier applier = new FieldPolicyApplier(
                Map.of("salary", new FieldPolicy(false, null, null, null, null)), null, null);
        assertThat(firstRow(applier.apply(data()))).doesNotContainKey("salary").containsKey("name");
    }

    @Test
    void masksByStrategy() {
        FieldPolicyApplier applier = new FieldPolicyApplier(
                Map.of("email", new FieldPolicy(null, null, "email", null, null)), null, null);
        assertThat(firstRow(applier.apply(data())).get("email")).isEqualTo("s***@example.com");
    }

    @Test
    void masksByClassificationDefault() {
        FieldPolicyApplier applier = new FieldPolicyApplier(
                Map.of("salary", new FieldPolicy(null, null, null, "business-confidential", null)),
                null, null);
        assertThat(firstRow(applier.apply(data())).get("salary")).isEqualTo("[MASKED]");
    }

    @Test
    void hidesPolicyGatedFieldWhenDenied() {
        PolicyEngine engine = new PolicyEngine(new SecurityConfig(
                Map.of("users.readSensitive", new Policy("users.readSensitive",
                        List.of(Policy.Rule.ofPermission("users:readSensitive")))),
                null));
        Principal principal = new Principal("u1", "sato", "Sato", null,
                List.of(), List.of("USER_READ"), List.of(), Map.of());

        FieldPolicyApplier applier = new FieldPolicyApplier(
                Map.of("salary", new FieldPolicy(null, "users.readSensitive", null, null, null)),
                engine, principal);
        assertThat(firstRow(applier.apply(data()))).doesNotContainKey("salary");
    }

    @Test
    void keepsPolicyGatedFieldWhenPermitted() {
        PolicyEngine engine = new PolicyEngine(new SecurityConfig(
                Map.of("users.readSensitive", new Policy("users.readSensitive",
                        List.of(Policy.Rule.ofPermission("users:readSensitive")))),
                null));
        Principal principal = new Principal("u1", "sato", "Sato", null,
                List.of(), List.of(), List.of("users:readSensitive"), Map.of());

        FieldPolicyApplier applier = new FieldPolicyApplier(
                Map.of("salary", new FieldPolicy(null, "users.readSensitive", null, null, null)),
                engine, principal);
        assertThat(firstRow(applier.apply(data())).get("salary")).isEqualTo(1000);
    }

    private static Object dataWithFlag(int inScope) {
        Map<String, Object> row = new java.util.LinkedHashMap<>(Map.of(
                "id", 1, "salary", 1000, "_in_scope", inScope));
        return Map.of("data", List.of(row));
    }

    @Test
    void unmaskWhenKeepsPlaintextForInScopeRowsAndStripsTheFlag() {
        FieldPolicyApplier applier = new FieldPolicyApplier(
                Map.of("salary", new FieldPolicy(null, null, "fixed", null, "_in_scope")),
                null, null);
        Map<String, Object> result = firstRow(applier.apply(dataWithFlag(1)));
        assertThat(result.get("salary")).isEqualTo(1000);
        assertThat(result).doesNotContainKey("_in_scope");
    }

    @Test
    void unmaskWhenMasksOutOfScopeRows() {
        FieldPolicyApplier applier = new FieldPolicyApplier(
                Map.of("salary", new FieldPolicy(null, null, "fixed", null, "_in_scope")),
                null, null);
        Map<String, Object> result = firstRow(applier.apply(dataWithFlag(0)));
        assertThat(result.get("salary")).isEqualTo("[MASKED]");
        assertThat(result).doesNotContainKey("_in_scope");
    }
}
