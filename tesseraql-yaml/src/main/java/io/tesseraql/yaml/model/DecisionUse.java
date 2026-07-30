package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * One entry of a route's {@code decide:} block (docs/decision-tables.md): a reference to a
 * shared decision plus this operation's wiring — each declared input resolved by a whitelist
 * expression against the request context ({@code params.total}, {@code principal.orgUnit},
 * {@code principal.role == 'officer'}). Cells stay comparisons; derivation lives here.
 *
 * @param use    the referenced decision's name under {@code decisions/}
 * @param params wiring of decision input name to a source expression; checked against the
 *               decision's inputs exactly at manifest load
 * @param effectiveAt the reference instant of a dated table-backed decision's
 *               {@code effective:} window — {@code audit.now} unless wired to a document date
 *               ({@code effectiveAt: params.postingDate}), which accounting-shaped decisions
 *               need
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DecisionUse(String use, Map<String, String> params, String effectiveAt,
        // The referenced decision, stamped in by the manifest loader (never authored): the
        // compiler builds the runtime table from the reference alone, the rule-sets line.
        @com.fasterxml.jackson.annotation.JsonIgnore DecisionsDocument.Decision decision) {

    public DecisionUse {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** This reference with the shared decision resolved underneath (manifest load only). */
    public DecisionUse resolvedWith(DecisionsDocument.Decision shared) {
        return new DecisionUse(use, params, effectiveAt, shared);
    }
}
