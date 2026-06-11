package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Request input handling policy (design ch. 33.1, 33.2).
 *
 * @param unknownFields        how to treat request fields with no declared input:
 *                             {@code reject} (default) or {@code ignore}
 * @param readOnlyFieldBehavior how to treat declared but non-writable fields when present:
 *                             {@code reject} (default), {@code ignore}, or {@code warn}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InputPolicy(String unknownFields, String readOnlyFieldBehavior) {

    public boolean rejectsUnknownFields() {
        return unknownFields == null || "reject".equals(unknownFields);
    }

    public String readOnlyBehaviorOrDefault() {
        return readOnlyFieldBehavior == null ? "reject" : readOnlyFieldBehavior;
    }

    public static InputPolicy defaults() {
        return new InputPolicy("reject", "reject");
    }
}
