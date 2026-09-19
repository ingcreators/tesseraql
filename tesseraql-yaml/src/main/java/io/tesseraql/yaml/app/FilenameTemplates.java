package io.tesseraql.yaml.app;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.files.FilenamePlaceholders;
import io.tesseraql.core.files.SplitExport;
import io.tesseraql.yaml.app.ExportDeclarations.Kind;
import io.tesseraql.yaml.app.ExportDeclarations.Site;
import io.tesseraql.yaml.app.ExportDeclarations.Violation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * A declared file name's placeholders against what the site that fixes the name can resolve
 * (docs/route-filename-placeholders.md decisions 3 and 5): a route's {@code export.filename},
 * {@code response.file.filename} and {@code response.stream.filename} resolve the request's
 * roots, a job step's {@code export.filename} and {@code push.as} the step context's. Judged
 * at lint and refused at build and boot from this one classification, so the surfaces cannot
 * drift on what a root is.
 *
 * <p>The runtime resolves whatever its context holds ({@link FilenamePlaceholders#resolve}
 * asks an {@code EvaluationContext}, which has no root list); the root set here is the lint's
 * knowledge of what that context will hold, stated once. A placeholder outside it renders
 * {@code _}, silently — the shape this class exists to refuse before a request does.
 */
public final class FilenameTemplates {

    /**
     * TQL-YAML-1076: a {@code filename:} placeholder the site cannot resolve — a spelling
     * outside the dotted-path grammar (letters, digits, {@code _} and {@code .} between the
     * braces), a root the request or the job context does not carry, a {@code params.<name>}
     * or {@code query.<name>} not declared under {@code input:}, or a {@code path.<name>} the
     * route's URL does not declare. The name would carry the braces or {@code _} where the
     * author expected a value.
     */
    public static final TqlErrorCode UNRESOLVABLE_PLACEHOLDER = new TqlErrorCode(TqlDomain.YAML,
            1076);

    /** The roots every route's request context carries, whoever asks. */
    private static final Set<String> REQUEST_ROOTS = Set.of("params", "query", "path", "body",
            "tenant", "request", "flags", "preference");

    /** The roots a job's step context resolves ({@code JobExecutor}'s context map). */
    private static final Set<String> JOB_ROOTS = Set.of("params", "steps", "batch", "tenant");

    private FilenameTemplates() {
    }

    /** How one written placeholder fails, or {@link Problem#KEY} for the split export's own. */
    public enum Problem {
        /** {@code WRITTEN} but not {@code RESOLVED}: the runtime delivers it braces on. */
        MALFORMED,
        /** {@code {key}} — legitimate on a filename, never on a push's delivered name. */
        KEY,
        /** The first segment names no root the site resolves. */
        UNKNOWN_ROOT,
        /** {@code params.<name>} or {@code query.<name>} the site's {@code input:} does not declare. */
        UNDECLARED_INPUT,
        /** {@code path.<name>} the route's URL path does not declare. */
        UNKNOWN_PATH_PARAMETER
    }

    /** One written placeholder and what is wrong with it. */
    public record Finding(Problem problem, String path) {
    }

    /**
     * Every written placeholder of {@code template} the site cannot resolve, in template order;
     * {@code extraRoots} are the roots the site holds beyond the request's or the job's (a
     * {@code response.file} renders after the route's sources ran, so their names resolve).
     */
    public static List<Finding> classify(String template, Site site, Set<String> extraRoots) {
        List<Finding> out = new ArrayList<>();
        if (template == null || template.indexOf('{') < 0) {
            return out;
        }
        Set<String> roots = site.job() ? JOB_ROOTS : REQUEST_ROOTS;
        Matcher written = FilenamePlaceholders.WRITTEN.matcher(template);
        while (written.find()) {
            String path = written.group(1);
            if (!FilenamePlaceholders.resolves(path)) {
                out.add(new Finding(Problem.MALFORMED, path));
                continue;
            }
            if (SplitExport.KEY.equals(written.group())) {
                out.add(new Finding(Problem.KEY, path));
                continue;
            }
            int dot = path.indexOf('.');
            String root = dot < 0 ? path : path.substring(0, dot);
            String rest = dot < 0 ? "" : path.substring(dot + 1);
            String name = rest.contains(".") ? rest.substring(0, rest.indexOf('.')) : rest;
            if (!roots.contains(root) && !extraRoots.contains(root)
                    && !("principal".equals(root) && !site.job() && site.principal())) {
                out.add(new Finding(Problem.UNKNOWN_ROOT, path));
            } else if (("params".equals(root) || "query".equals(root))
                    && !site.inputs().contains(name)) {
                out.add(new Finding(Problem.UNDECLARED_INPUT, path));
            } else if ("path".equals(root) && !site.pathParams().contains(name)) {
                out.add(new Finding(Problem.UNKNOWN_PATH_PARAMETER, path));
            }
        }
        return out;
    }

    /**
     * The 1076 violations of a {@code filename:} at {@code key} ({@code export.filename},
     * {@code response.file.filename}, …); {@code {key}} is not this rule's — a split's
     * placeholder is judged by the incomplete-export rule and the split writer's own refusal.
     */
    public static List<Violation> violations(Site site, String key, String template,
            Set<String> extraRoots) {
        List<Violation> out = new ArrayList<>();
        for (Finding finding : classify(template, site, extraRoots)) {
            String sentence = switch (finding.problem()) {
                case MALFORMED -> "is not a dotted path of letters, digits, _ and . - the runtime"
                        + " resolves no other spelling and delivers it literally";
                case KEY -> null;
                case UNKNOWN_ROOT -> "names no " + (site.job() ? "job context" : "request")
                        + " root (" + (site.job()
                                ? "params, steps, batch, tenant"
                                : "params, query, path, body, tenant, request, flags,"
                                        + " preference" + (site.principal()
                                                ? ", principal"
                                                : "; principal on an authenticated route")
                                        + (extraRoots.isEmpty()
                                                ? ""
                                                : ", or a declared source"))
                        + ") - it would render _";
                case UNDECLARED_INPUT -> "names an input the "
                        + (site.job() ? "job" : "route") + " does not declare under input: -"
                        + " it would render _";
                case UNKNOWN_PATH_PARAMETER -> "names a path parameter the route's URL does not"
                        + " declare - it would render _";
            };
            if (sentence != null) {
                out.add(new Violation(UNRESOLVABLE_PLACEHOLDER, Kind.INVALID, key,
                        site.prefix(key) + "placeholder {" + ExportDeclarations.bounded(
                                finding.path()) + "} " + sentence));
            }
        }
        return out;
    }
}
