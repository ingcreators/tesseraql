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
        String domain,
        // Presentation hint (docs/view-composition.md wave 3a): the form widget this field
        // renders as, declared once on its domain ("an SKU is a code input"). Never part of the
        // HTTP contract — OpenAPI emission excludes it — and a per-view fields: override wins.
        String widget,
        // The code catalog this field's values come from (docs/lookups.md, decision 9): the
        // legal values are that catalog's active codes, so "a 取引区分 is one of the codes in
        // the 区分マスタ" is declared once instead of restated as an enum that drifts from the
        // table it describes.
        String codes,
        // Write authorization (docs/view-composition.md wave 4): a policy the principal must
        // satisfy to supply this field. Operational like required/writable — never accepted
        // inside a domain — enforced at the binder (a failing principal's value follows the
        // route's readOnly behavior) and mirrored by the rendered form, which omits the field.
        String policy,
        // What this field is, in the words a caller reads (docs/prompt-as-recipe.md decision 4).
        // It is a wire field on both MCP surfaces derived from input:: an argument of a
        // prompts/list prompt carries name/description/required, and a tool's inputSchema is
        // JSON Schema, whose description is the hint a model follows when choosing a value. Not
        // operational, so a domain may carry it and a field that declares none inherits it —
        // what an SKU is has one home, like its type and its pattern.
        String description) {

    /**
     * The keys that belong to a route's <em>use</em> of a field rather than to the field itself
     * (docs/field-domains.md). A shared domain describes what an SKU is; whether this operation
     * requires one, defaults it, lets the request supply it, or gates it behind a policy is the
     * operation's business — so a {@code domains/} document declaring one of these is refused
     * (TQL-FIELD-4602), and the remaining keys are exactly what a domain may carry.
     */
    public static final java.util.Set<String> OPERATIONAL_KEYS = java.util.Set.of("required",
            "requiredWhen", "default", "writable", "policy", "domain");

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
     * {@code default}, {@code writable}, {@code policy} — are never taken from the domain, which
     * cannot declare them.
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
                domain,
                widget != null ? widget : d.widget(),
                codes != null ? codes : d.codes(),
                policy,
                description != null ? description : d.description());
    }

    /** Element type for array inputs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InputItems(String type, @JsonProperty("enum") List<String> enumValues) {

        public InputItems {
            // Absent means "no element enum", not null: every reader would otherwise repeat the
            // same null check, and the first one to forget it gets an NPE at request time.
            enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        }
    }
}
