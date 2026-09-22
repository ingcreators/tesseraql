package io.tesseraql.yaml.view;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.InputField;
import io.tesseraql.yaml.model.RouteDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one judgement of a list view's {@code exports:} (docs/list-export.md decision 6): each
 * entry names a {@code query-export} GET route or a {@code file-export} POST route, and that
 * route accepts the list's question — every input the list route declares, with the same type,
 * a sort allowlist at least the list's, no required input the kick-off never sends, no path
 * parameter the list's path lacks ({@code TQL-VIEW-3331}). The lint and the compiler both ask
 * here, so a view the lint passes is a view the build binds; the kick-off would otherwise
 * answer 400 on the first click, or a different question.
 */
public final class ViewExports {

    /**
     * TQL-VIEW-3331: an exports: entry targets a route that is not a query-export GET or a
     * file-export POST, or one that would refuse the list's question (a list input it does not
     * declare or declares with another type, a sort allowlist narrower than the list's, a
     * required input the kick-off never sends, a path parameter the list route lacks).
     */
    public static final TqlErrorCode UNMATCHED_EXPORT = new TqlErrorCode(TqlDomain.VIEW, 3331);

    public static final String QUERY_EXPORT = "query-export";
    public static final String FILE_EXPORT = "file-export";

    /**
     * The framework's window and selection fields a kick-off never carries (docs/list-export.md
     * decision 2): the page, its size, the keyset cursor, the snapshot membership and the
     * checked rows are how the page is read, not what it asks. A list, read by membership only:
     * the declaration layer's ordered-copy ledger admits no new salt-capable set.
     */
    public static final List<String> WINDOW = List.of("page", "size", "after", "keys", "ids");

    private static final Pattern PATH_PARAM = Pattern.compile("\\{([^}/]+)}");

    private ViewExports() {
    }

    /** One way an export route would refuse the list's question, in the words both altitudes raise. */
    public record Violation(String viewId, String action, String reason) {

        public String message() {
            return "view " + viewId + ": export " + action + " " + reason;
        }
    }

    /** Whether {@code method} and {@code recipe} are one of the two export shapes. */
    public static boolean isExportRoute(String method, String recipe) {
        return ("GET".equalsIgnoreCase(method) && QUERY_EXPORT.equals(recipe))
                || ("POST".equalsIgnoreCase(method) && FILE_EXPORT.equals(recipe));
    }

    /**
     * The export route an entry names: the route at {@code action} whose method and recipe are
     * an export shape, or empty. A page route at the same path (a {@code query-html} GET beside
     * a {@code file-export} POST) is not it.
     */
    public static Optional<RouteFile> target(String action,
            Function<String, List<RouteFile>> routesByPath) {
        List<RouteFile> candidates = routesByPath.apply(action);
        if (candidates == null) {
            return Optional.empty();
        }
        return candidates.stream()
                .filter(route -> route.definition() != null
                        && isExportRoute(route.httpMethod(), route.definition().recipe()))
                .findFirst();
    }

    /** The {@code {name}} parameters of a URL path, in path order. */
    public static List<String> pathParams(String urlPath) {
        List<String> names = new ArrayList<>();
        if (urlPath == null) {
            return names;
        }
        Matcher matcher = PATH_PARAM.matcher(urlPath);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /**
     * Every way {@code spec}'s exports would fail against {@code listRoute} (the route that
     * renders the list, at {@code listPath}), in declaration order. {@code routesByPath}
     * answers the routes mounted at a path — all of them, because one path may carry a GET and
     * a POST.
     */
    public static List<Violation> violations(ViewSpec spec, RouteDefinition listRoute,
            String listPath, Function<String, List<RouteFile>> routesByPath) {
        List<Violation> violations = new ArrayList<>();
        if (spec == null || spec.exports().isEmpty()) {
            return violations;
        }
        Map<String, InputField> listInputs = listRoute == null || listRoute.input() == null
                ? Map.of()
                : listRoute.input();
        List<String> listPathParams = pathParams(listPath);
        for (ViewSpec.Export export : spec.exports()) {
            Optional<RouteFile> found = target(export.action(), routesByPath);
            if (found.isEmpty()) {
                violations.add(new Violation(spec.id(), export.action(),
                        "targets no query-export GET route or file-export POST route"
                                + describeOthers(export.action(), routesByPath)));
                continue;
            }
            RouteFile route = found.get();
            Map<String, InputField> exportInputs = route.definition().input() == null
                    ? Map.of()
                    : route.definition().input();
            for (Map.Entry<String, InputField> declared : listInputs.entrySet()) {
                String name = declared.getKey();
                if (WINDOW.contains(name)) {
                    continue;
                }
                InputField listField = declared.getValue();
                InputField exportField = exportInputs.get(name);
                if (exportField == null) {
                    violations.add(new Violation(spec.id(), export.action(),
                            "does not declare the list's input '" + name
                                    + "' — the kick-off would be refused as an unknown field"));
                    continue;
                }
                String listType = typeOf(listField);
                String exportType = typeOf(exportField);
                if (!listType.equals(exportType)) {
                    violations.add(new Violation(spec.id(), export.action(),
                            "declares '" + name + "' as " + exportType + " where the list"
                                    + " declares " + listType));
                    continue;
                }
                if ("sort".equals(listType) && listField.columns() != null) {
                    List<String> allowed = exportField.columns() == null
                            ? List.of()
                            : exportField.columns();
                    for (String column : listField.columns()) {
                        if (!allowed.contains(column)) {
                            violations.add(new Violation(spec.id(), export.action(),
                                    "sort allowlist lacks '" + column
                                            + "', which the list's sort admits"));
                        }
                    }
                }
            }
            for (Map.Entry<String, InputField> declared : exportInputs.entrySet()) {
                String name = declared.getKey();
                if (listInputs.containsKey(name) || !declared.getValue().required()) {
                    continue;
                }
                violations.add(new Violation(spec.id(), export.action(),
                        "requires '" + name + "', which the kick-off never sends"));
            }
            for (String parameter : pathParams(route.urlPath())) {
                if (!listPathParams.contains(parameter)) {
                    violations.add(new Violation(spec.id(), export.action(),
                            "declares path parameter {" + parameter + "}, which the list"
                                    + " route's path does not"));
                }
            }
        }
        return violations;
    }

    /** The declared type, {@code string} when the declaration says nothing. */
    private static String typeOf(InputField field) {
        return field == null || field.type() == null || field.type().isBlank()
                ? "string"
                : field.type();
    }

    /** What is mounted at the path instead, so the sentence names the mistake. */
    private static String describeOthers(String action,
            Function<String, List<RouteFile>> routesByPath) {
        List<RouteFile> candidates = routesByPath.apply(action);
        if (candidates == null || candidates.isEmpty()) {
            return "";
        }
        StringBuilder others = new StringBuilder(" (mounted there: ");
        for (int i = 0; i < candidates.size(); i++) {
            RouteFile candidate = candidates.get(i);
            if (i > 0) {
                others.append(", ");
            }
            others.append(candidate.httpMethod()).append(' ')
                    .append(candidate.definition() == null
                            ? "?"
                            : candidate.definition().recipe());
        }
        return others.append(')').toString();
    }
}
