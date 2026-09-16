package io.tesseraql.yaml.app;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.yaml.app.ExportDeclarations.Kind;
import io.tesseraql.yaml.app.ExportDeclarations.Violation;
import io.tesseraql.yaml.model.Binding;
import io.tesseraql.yaml.model.RouteDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where a document reads the request from, judged where the document is written
 * (docs/audit-low-leads.md slice 9): every {@code params:} entry of a source, a step, an
 * enrichment, a validation rule and an export's {@code after:} statement, and an export's
 * {@code timezone:}/{@code locale:} — each a source expression such as {@code query.q},
 * {@code body.email} or {@code header.Cookie}.
 *
 * <p>An input is fed by what the route declares. A request header is not an input: it never
 * was declared as one, every browser sends a dozen of them under ordinary names, and the
 * binder that fell back to a same-named header let {@code Host} satisfy {@code required: true}
 * and a {@code Priority} header refuse a shipped example's list page. The one place a header
 * is a legitimate source is a service provider's arguments — the stack shells forward the
 * caller's session to the member they delegate to — so {@code header.<name>} is a source on a
 * {@code service:} binding's {@code params:} and nowhere else, read from the wire and never
 * overridden by a query parameter or a body field of that name. One predicate on both
 * altitudes: the linter reports from it and the compiler refuses from it.
 */
public final class RequestSources {

    /**
     * TQL-YAML-1069: a {@code header.<name>} source outside a {@code service:} binding's
     * {@code params:} — SQL binds what the route declares under {@code input:}, a request
     * header is a provider's argument — or one that names no header. Reported at lint and
     * refused at boot from the same predicate.
     */
    public static final TqlErrorCode HEADER_OUTSIDE_SERVICE = new TqlErrorCode(TqlDomain.YAML,
            1069);

    /** The prefix a declared header source carries: {@code header.Cookie}. */
    public static final String HEADER_PREFIX = "header.";

    private RequestSources() {
    }

    /**
     * The header a source expression names, or empty when the expression is not a header
     * source. {@code header.} with nothing after it names no header and is empty here too — the
     * predicate reports it, the binder has nothing to read.
     */
    public static Optional<String> headerName(String source) {
        if (source == null || !source.startsWith(HEADER_PREFIX)
                || source.length() == HEADER_PREFIX.length()) {
            return Optional.empty();
        }
        return Optional.of(source.substring(HEADER_PREFIX.length()));
    }

    /**
     * One request-sourced expression a document carries.
     *
     * @param slot       a dotted label naming where it hangs ({@code sources.main},
     *                   {@code steps.write.enrich.customer}, {@code validate.unique},
     *                   {@code export.after}, {@code export.timezone})
     * @param bind       the bind name under {@code params:}, or null for a scalar key
     * @param expression the source expression as written
     * @param service    whether the slot is a {@code service:} binding's own {@code params:}
     */
    public record Source(String slot, String bind, String expression, boolean service) {

        /** The expression's root — {@code query}, {@code body}, {@code header}, … */
        public String root() {
            int dot = expression.indexOf('.');
            return dot < 0 ? expression : expression.substring(0, dot);
        }

        /** The first segment after the root, or empty: the input a request source names. */
        public String name() {
            int dot = expression.indexOf('.');
            if (dot < 0) {
                return "";
            }
            String rest = expression.substring(dot + 1);
            int next = rest.indexOf('.');
            return next < 0 ? rest : rest.substring(0, next);
        }

        /**
         * Where the expression is written, for a sentence: {@code sources.main params: q}, or
         * the scalar's own key ({@code export.timezone}).
         */
        public String where() {
            return bind == null ? slot : slot + " params: " + bind;
        }
    }

    /**
     * Every source expression the document carries, in authored order: each binding's
     * {@code params:} under {@code sources:} and {@code steps:}, the {@code params:} of every
     * enrichment hanging off them, each {@code validate:} rule's, an export's {@code after:}
     * statement's, and the export's {@code timezone:} and {@code locale:} scalars.
     */
    public static List<Source> sources(RouteDefinition definition) {
        List<Source> out = new ArrayList<>();
        definition.sources().forEach((name, binding) -> binding(out, "sources." + name,
                binding));
        definition.steps().forEach((name, binding) -> binding(out, "steps." + name, binding));
        definition.validate().forEach((name, rule) -> params(out, "validate." + name,
                rule.params(), false));
        if (definition.fileExport() != null) {
            if (definition.fileExport().after() != null
                    && definition.fileExport().after().sql() != null) {
                params(out, "export.after", definition.fileExport().after().sql().params(),
                        false);
            }
            scalar(out, "export.timezone", definition.fileExport().timezone());
            scalar(out, "export.locale", definition.fileExport().locale());
        }
        return out;
    }

    private static void binding(List<Source> out, String slot, Binding binding) {
        params(out, slot, binding.params(), binding.isService());
        if (binding.enrich() == null) {
            return;
        }
        binding.enrich().forEach((name, enrich) -> {
            if (enrich.sql() != null) {
                params(out, slot + ".enrich." + name, enrich.sql().params(), false);
            }
        });
    }

    private static void params(List<Source> out, String slot, Map<String, String> params,
            boolean service) {
        if (params == null) {
            return;
        }
        params.forEach((bind, expression) -> {
            if (expression != null) {
                out.add(new Source(slot, bind, expression, service));
            }
        });
    }

    /** A scalar key that reads a source expression rather than a literal (the export's rule). */
    private static void scalar(List<Source> out, String slot, String value) {
        if (ExportDeclarations.isSourceExpression(value)) {
            out.add(new Source(slot, null, value, false));
        }
    }

    /**
     * Every header source the document writes where the binder does not read one: outside a
     * {@code service:} binding's {@code params:}, or naming no header at all.
     */
    public static List<Violation> violations(String app, RouteDefinition definition,
            RecipeShape.Surface surface) {
        List<Violation> out = new ArrayList<>();
        String subject = surface.noun() + " '" + ExportDeclarations.bounded(definition.id())
                + "'";
        for (Source source : sources(definition)) {
            if (!source.expression().startsWith(HEADER_PREFIX)
                    && !"header".equals(source.expression())) {
                continue;
            }
            String head = "app '" + ExportDeclarations.bounded(app) + "': " + subject + " "
                    + source.where() + ": '" + ExportDeclarations.bounded(source.expression())
                    + "' ";
            if (!source.service()) {
                out.add(new Violation(HEADER_OUTSIDE_SERVICE, Kind.INVALID, source.slot(),
                        head + "reads a request header, which only a service: binding's"
                                + " params: may - a statement binds what the route declares"
                                + " under input:, so declare the input and read query.<name>"
                                + " or body.<name>"));
            } else if (headerName(source.expression()).isEmpty()) {
                out.add(new Violation(HEADER_OUTSIDE_SERVICE, Kind.INVALID, source.slot(),
                        head + "names no header - the form is header.<Name>, the header's"
                                + " name as the wire carries it (header.Cookie)"));
            }
        }
        return out;
    }
}
