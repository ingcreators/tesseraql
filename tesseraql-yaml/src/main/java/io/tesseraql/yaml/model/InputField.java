package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Declaration of a single route input parameter (design ch. 6.3).
 *
 * <p>Inputs are whitelisted: only declared fields are bound from the request, and constraints
 * here drive validation and default application during request binding.
 *
 * <p>{@code date}/{@code datetime}/{@code number} inputs parse with the negotiated request
 * locale and the declared {@code format} pattern (roadmap Phase 22), mirroring the file-transfer
 * column mappings: {@link java.time.format.DateTimeFormatter} patterns for temporal inputs,
 * {@link java.text.DecimalFormat} for numbers — so {@code 2026/06/12} or {@code 1.234,56} bind
 * as typed SQL parameters per the user's locale.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InputField(
        String type,
        boolean required,
        @JsonProperty("default") Object defaultValue,
        java.math.BigDecimal min,
        java.math.BigDecimal max,
        Integer maxLength,
        @JsonProperty("enum") List<String> enumValues,
        Boolean writable,
        String classification,
        String mask,
        String format,
        InputItems items,
        String pattern,
        Integer minLength,
        String requiredWhen,
        // Reference to an app-level field domain (docs/field-domains.md); the manifest loader
        // merges the domain's keys under this field's own, so downstream consumers see the
        // fully-populated result (with this reference kept for tooling and lint).
        String domain) {

    /** The semantic string formats {@code format:} validates (roadmap Phase 40). */
    public static final java.util.Set<String> STRING_FORMATS = java.util.Set.of("email", "uuid",
            "url");

    /** Whether this field's {@code format:} is a semantic string validator (vs a parse pattern). */
    public boolean hasStringFormat() {
        return (type == null || "string".equals(type)) && format != null
                && STRING_FORMATS.contains(format);
    }

    /** Whether this field may be supplied by the request (design ch. 33.2). Defaults to true. */
    public boolean isWritable() {
        return writable == null || writable;
    }

    /**
     * This field with the referenced domain's keys merged underneath (docs/field-domains.md):
     * route-declared keys win, and the operational keys — {@code required}, {@code requiredWhen},
     * {@code default}, {@code writable} — are never taken from the domain, which cannot declare
     * them.
     */
    public InputField mergedWith(InputField d) {
        return new InputField(
                type != null ? type : d.type(),
                required,
                defaultValue,
                min != null ? min : d.min(),
                max != null ? max : d.max(),
                maxLength != null ? maxLength : d.maxLength(),
                enumValues != null ? enumValues : d.enumValues(),
                writable,
                classification != null ? classification : d.classification(),
                mask != null ? mask : d.mask(),
                format != null ? format : d.format(),
                items != null ? items : d.items(),
                pattern != null ? pattern : d.pattern(),
                minLength != null ? minLength : d.minLength(),
                requiredWhen,
                domain);
    }

    /** Element type for array inputs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InputItems(String type, @JsonProperty("enum") List<String> enumValues) {
    }
}
