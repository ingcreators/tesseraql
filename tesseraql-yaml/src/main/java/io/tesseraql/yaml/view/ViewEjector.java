package io.tesseraql.yaml.view;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.scaffold.ScaffoldedFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The customization ladder's L3 (docs/declarative-views.md): renders a view document's pattern
 * ONCE into a real, hand-ownable Thymeleaf template — the generated file carries the scaffold
 * checksum header so edit detection applies — and the route flips from {@code view:} to
 * {@code template:}. Ejecting pins the layout: a list or detail view must declare its
 * {@code columns:}/{@code fields:} explicitly (render-time derivation has no static equivalent),
 * and labels are emitted as literals the author owns from then on.
 *
 * <p>Shares {@link ViewFields} with the render-time binding so the emitted widgets and
 * constraints are exactly what the pattern would have rendered.
 */
public final class ViewEjector {

    private static final Pattern LINK_PLACEHOLDER = io.tesseraql.core.sql.SqlIdentifiers.PLACEHOLDER;

    private ViewEjector() {
    }

    /**
     * Generates the ejected template for a view. {@code fields} carries a form view's derived
     * definitions (empty for list/detail); {@code targetPath} is the app-home-relative path the
     * template will live at (drives the checksum stamp).
     */
    public static ScaffoldedFile eject(Path appHome, Path routeDir, String viewRef,
            ViewSpec spec, List<ViewFields.FieldDef> fields, String targetPath) {
        return eject(appHome, routeDir, viewRef, spec, fields, targetPath, id -> {
            throw new TqlException(ViewSpec.INVALID_VIEW,
                    "No embedded-view resolver — cannot eject an embedding view here");
        });
    }

    /**
     * The composite variant (docs/view-composition.md wave 2c): {@code embedTemplate} resolves
     * an embedded view id to its entry template's engine name — the ejected page pins the host
     * layout while embedded views stay declarative, inserted as
     * {@code ~{<pattern> :: view(${views['<id>']})}} against the flipped route's
     * {@code views:} models.
     */
    public static ScaffoldedFile eject(Path appHome, Path routeDir, String viewRef,
            ViewSpec spec, List<ViewFields.FieldDef> fields, String targetPath,
            java.util.function.Function<String, String> embedTemplate) {
        java.util.Map<String, String> codes = catalogByColumn(appHome, viewRef, spec);
        String body = switch (spec.view()) {
            case ViewSpec.LIST -> list(appHome, routeDir, spec, codes);
            case ViewSpec.DETAIL -> detail(appHome, routeDir, spec, codes, embedTemplate);
            case ViewSpec.FORM -> form(appHome, routeDir, spec, fields);
            case ViewSpec.DASHBOARD -> dashboard(appHome, routeDir, spec, codes, embedTemplate);
            default -> throw new TqlException(ViewSpec.INVALID_VIEW,
                    "Cannot eject view kind " + spec.view());
        };
        String content = "<!DOCTYPE html>\n"
                + "<!-- Ejected from " + viewRef + " (tesseraql scaffold eject-view): the"
                + " tql/view/" + spec.view() + " pattern pinned to this page. The view document"
                + " no longer drives rendering; edit freely. -->\n"
                + body;
        return new ScaffoldedFile(targetPath, content);
    }

    /**
     * Flips the route definition from {@code view:} to {@code template:}. Fails when the
     * {@code view:} line is not found (the route was edited underneath the eject).
     */
    public static String flipRoute(String routeYaml, String viewRef, String templateName) {
        return flipRoute(routeYaml, viewRef, templateName, List.of());
    }

    /**
     * The composite variant: the ejected host's embedded views stay declarative, so the flip
     * also writes the {@code views:} binding their models render through
     * (docs/view-composition.md wave 2c).
     */
    public static String flipRoute(String routeYaml, String viewRef, String templateName,
            List<String> embeddedIds) {
        Pattern line = Pattern.compile("(?m)^(\\s*)view:\\s*" + Pattern.quote(viewRef)
                + "\\s*$");
        Matcher matcher = line.matcher(routeYaml);
        if (!matcher.find()) {
            throw new TqlException(ViewSpec.INVALID_VIEW, "The route does not declare 'view: "
                    + viewRef + "' — cannot flip it to template:");
        }
        String replacement = "$1template: " + Matcher.quoteReplacement(templateName);
        if (!embeddedIds.isEmpty()) {
            replacement += "\n$1views: [" + Matcher.quoteReplacement(
                    String.join(", ", embeddedIds)) + "]";
        }
        return matcher.replaceFirst(replacement);
    }

    private static String list(Path appHome, Path routeDir, ViewSpec spec,
            java.util.Map<String, String> codes) {
        require(!spec.columns().isEmpty(),
                "a list view needs explicit columns: before ejecting — the template pins them");
        StringBuilder html = pageOpen(spec);
        html.append("<section class=\"hc-card\">\n");
        titleCluster(html, appHome, routeDir, spec);
        html.append("  <div class=\"hc-datagrid\">\n"
                + "    <div class=\"hc-datagrid__scroll\">\n"
                + "      <table class=\"hc-datagrid__table\">\n"
                + "        <thead class=\"hc-datagrid__head\">\n"
                + "          <tr>\n");
        for (ViewSpec.Column column : spec.columns()) {
            html.append("            <th class=\"hc-datagrid__headcell\">")
                    .append(escape(label(column))).append("</th>\n");
        }
        html.append("          </tr>\n"
                + "        </thead>\n"
                + "        <tbody class=\"hc-datagrid__body\">\n"
                + "          <tr class=\"hc-datagrid__row\" th:each=\"row : ${")
                .append(spec.source()).append(".rows}\">\n");
        for (ViewSpec.Column column : spec.columns()) {
            cell(html, column, codes, "            ");
        }
        html.append("          </tr>\n"
                + "        </tbody>\n"
                + "      </table>\n"
                + "    </div>\n"
                + "  </div>\n");
        slot(html, appHome, routeDir, spec, "footer", "  ");
        html.append("</section>\n");
        return pageClose(html);
    }

    private static String detail(Path appHome, Path routeDir, ViewSpec spec,
            java.util.Map<String, String> codes,
            java.util.function.Function<String, String> embedTemplate) {
        require(!spec.fields().isEmpty(),
                "a detail view needs explicit fields: before ejecting — the template pins them");
        for (ViewSpec.Child child : spec.children()) {
            require(child.view() != null || !child.columns().isEmpty(),
                    "child " + child.source()
                            + " needs explicit columns: before ejecting — the template pins them");
        }
        StringBuilder html = pageOpen(spec);
        html.append("<section class=\"hc-card\" th:with=\"row=${#lists.isEmpty(")
                .append(spec.source()).append(".rows) ? null : ")
                .append(spec.source()).append(".rows[0]}\">\n");
        titleCluster(html, appHome, routeDir, spec);
        html.append("  <div class=\"hc-stack\">\n");
        for (ViewSpec.Field field : spec.fields()) {
            String labelText = field.label() != null
                    ? field.label()
                    : ViewFields.humanize(field.name());
            html.append("    <div class=\"hc-field\">\n"
                    + "      <span class=\"hc-field__label\">").append(escape(labelText))
                    .append("</span>\n"
                            + "      <span th:text=\"${row == null ? '' : ")
                    .append(value(codes, field.name(), "row")).append("}\">")
                    .append(field.name())
                    .append("</span>\n"
                            + "    </div>\n");
        }
        html.append("  </div>\n");
        for (ViewSpec.Child child : spec.children()) {
            if (child.view() != null) {
                // The embedded view stays declarative (wave 2c): the flipped route's views:
                // binding renders its model, inserted here through its own pattern.
                html.append("  <th:block th:insert=\"~{").append(embedTemplate.apply(
                        child.view())).append(" :: view(${views['").append(child.view())
                        .append("']})}\"/>\n");
                continue;
            }
            String childTitle = child.title() != null
                    ? child.title()
                    : ViewFields.humanize(child.source());
            html.append("  <section>\n    <h3>").append(escape(childTitle)).append("</h3>\n"
                    + "    <div class=\"hc-datagrid\">\n"
                    + "      <div class=\"hc-datagrid__scroll\">\n"
                    + "        <table class=\"hc-datagrid__table\">\n"
                    + "          <thead class=\"hc-datagrid__head\">\n"
                    + "            <tr>\n");
            for (ViewSpec.Column column : child.columns()) {
                html.append("              <th class=\"hc-datagrid__headcell\">")
                        .append(escape(label(column))).append("</th>\n");
            }
            html.append("            </tr>\n"
                    + "          </thead>\n"
                    + "          <tbody class=\"hc-datagrid__body\">\n"
                    + "            <tr class=\"hc-datagrid__row\" th:each=\"child : ${")
                    .append(child.source()).append(".rows}\">\n");
            for (ViewSpec.Column column : child.columns()) {
                childCell(html, column, codes, "              ");
            }
            html.append("            </tr>\n"
                    + "          </tbody>\n"
                    + "        </table>\n"
                    + "      </div>\n"
                    + "    </div>\n"
                    + "  </section>\n");
        }
        slot(html, appHome, routeDir, spec, "footer", "  ");
        html.append("</section>\n");
        return pageClose(html);
    }

    /**
     * The dashboard pattern pinned to static markup (docs/pages-and-mail-lints.md
     * follow-ups): the kit's {@code hc-grid} of panel cards. A stat reads its column off
     * the first row, a sparkline joins its column with an OGNL projection, a chart emits
     * the {@code data-hc-chart} figure with its source table (the data, the no-JS
     * fallback, and the screen-reader representation in one), and a table panel reuses
     * the list cells. A sparkline's {@code data-max} is dropped — the render-time
     * derivation has no static equivalent; the hand-owned template can pin one.
     */
    private static String dashboard(Path appHome, Path routeDir, ViewSpec spec,
            java.util.Map<String, String> codes,
            java.util.function.Function<String, String> embedTemplate) {
        for (ViewSpec.Panel panel : spec.panels()) {
            switch (panel.type()) {
                case "stat", "sparkline" -> require(panel.column() != null,
                        "a " + panel.type() + " panel needs column: before ejecting");
                case "chart" -> require(panel.x() != null && !panel.effectiveSeries().isEmpty(),
                        "a chart panel needs x: and series:/y: before ejecting");
                case "table" -> require(!panel.columns().isEmpty(),
                        "a table panel needs explicit columns: before ejecting"
                                + " — the template pins them");
                case "view" -> require(panel.view() != null,
                        "a view panel needs view: before ejecting");
                default -> throw new TqlException(ViewSpec.INVALID_VIEW,
                        "Cannot eject panel type " + panel.type());
            }
        }
        StringBuilder html = pageOpen(spec);
        titleCluster(html, appHome, routeDir, spec);
        html.append("<div class=\"hc-grid\">\n");
        boolean hasChart = false;
        for (ViewSpec.Panel panel : spec.panels()) {
            if ("view".equals(panel.type())) {
                // The embedded view stays declarative (wave 2c): its model rides the flipped
                // route's views: binding, inserted through its own pattern (card included).
                html.append("  <th:block th:insert=\"~{").append(embedTemplate.apply(
                        panel.view())).append(" :: view(${views['").append(panel.view())
                        .append("']})}\"/>\n");
                continue;
            }
            String source = panel.source() != null ? panel.source() : spec.source();
            String title = panel.title() != null
                    ? panel.title()
                    : ViewFields.humanize(source);
            html.append("  <section class=\"hc-card\">\n"
                    + "    <div class=\"hc-card__header\"><h3>").append(escape(title))
                    .append("</h3></div>\n"
                            + "    <div class=\"hc-card__body hc-stack\">\n");
            switch (panel.type()) {
                case "stat" -> html.append("      <p><strong th:text=\"${#lists.isEmpty(")
                        .append(source).append(".rows) ? '—' : ").append(source)
                        .append(".rows[0]").append(key(panel.column()))
                        .append("}\">0</strong></p>\n");
                // The OGNL projection `rows.{col}` reads the column off each row map —
                // `#this` is forbidden by Thymeleaf 3.1's restricted evaluation, the bare
                // property form is not.
                case "sparkline" -> html.append("      <span class=\"hc-sparkline\""
                        + " data-min=\"0\" th:attr=\"data-values=${#strings.listJoin(")
                        .append(source).append(".rows.{").append(panel.column())
                        .append("}, ',')}\" aria-label=\"").append(escape(title))
                        .append("\"></span>\n");
                case "chart" -> {
                    hasChart = true;
                    html.append("      <figure class=\"hc-chart\" data-hc-chart=\"")
                            .append(panel.kind() == null ? "bar" : panel.kind()).append("\"")
                            .append(attr("data-x-type", panel.xType()))
                            .append(attr("data-height", panel.height()))
                            .append(attr("data-legend", panel.legend()))
                            .append(attr("data-y-label", panel.yLabel()))
                            .append(">\n"
                                    + "        <table class=\"hc-table\">\n"
                                    + "          <caption>")
                            .append(escape(title)).append("</caption>\n"
                                    + "          <thead><tr>\n"
                                    + "            <th>")
                            .append(escape(ViewFields.humanize(panel.x()))).append("</th>\n");
                    for (ViewSpec.Series series : panel.effectiveSeries()) {
                        String label = series.label() != null
                                ? series.label()
                                : ViewFields.humanize(series.column());
                        html.append("            <th")
                                .append(attr("data-mark", series.mark())).append(">")
                                .append(escape(label)).append("</th>\n");
                    }
                    html.append("          </tr></thead>\n"
                            + "          <tbody>\n"
                            + "            <tr th:each=\"row : ${").append(source)
                            .append(".rows}\">\n"
                                    + "              <td th:text=\"${row")
                            .append(key(panel.x())).append("}\">x</td>\n");
                    for (ViewSpec.Series series : panel.effectiveSeries()) {
                        html.append("              <td th:text=\"${row")
                                .append(key(series.column())).append("}\">v</td>\n");
                    }
                    html.append("            </tr>\n"
                            + "          </tbody>\n"
                            + "        </table>\n"
                            + "      </figure>\n");
                }
                case "table" -> {
                    html.append("      <div class=\"hc-datagrid\">\n"
                            + "        <div class=\"hc-datagrid__scroll\">\n"
                            + "          <table class=\"hc-datagrid__table\">\n"
                            + "            <thead class=\"hc-datagrid__head\">\n"
                            + "              <tr>\n");
                    for (ViewSpec.Column column : panel.columns()) {
                        html.append("                <th class=\"hc-datagrid__headcell\">")
                                .append(escape(label(column))).append("</th>\n");
                    }
                    html.append("              </tr>\n"
                            + "            </thead>\n"
                            + "            <tbody class=\"hc-datagrid__body\">\n"
                            + "              <tr class=\"hc-datagrid__row\" th:each=\"row : ${")
                            .append(source).append(".rows}\">\n");
                    for (ViewSpec.Column column : panel.columns()) {
                        cell(html, column, codes, "                ");
                    }
                    html.append("              </tr>\n"
                            + "            </tbody>\n"
                            + "          </table>\n"
                            + "        </div>\n"
                            + "      </div>\n");
                }
                default -> throw new IllegalStateException(panel.type());
            }
            html.append("    </div>\n  </section>\n");
        }
        html.append("</div>\n");
        slot(html, appHome, routeDir, spec, "footer", "");
        if (hasChart) {
            // installChart needs Observable Plot; both self-hosted, CSP stays 'self'. Ejected as
            // link expressions like the pattern they replace, so the ejected page keeps working
            // under a base path — and shows the author the idiom (docs/base-path.md).
            html.append("<script th:src=\"@{/assets/vendor/observablehq__plot/dist/"
                    + "plot.umd.min.js}\" defer></script>\n"
                    + "<script type=\"module\" th:src=\"@{/assets/_tesseraql/charts.js}\">"
                    + "</script>\n");
        }
        return pageClose(html);
    }

    private static String form(Path appHome, Path routeDir, ViewSpec spec,
            List<ViewFields.FieldDef> fields) {
        require(fields != null && !fields.isEmpty(),
                "a form view ejects from its derived fields — the action route declares none");
        String formId = spec.id().replace('.', '-') + "-form";
        StringBuilder html = pageOpen(spec);
        html.append("<section class=\"hc-card\" th:with=\"row=${#lists.isEmpty(")
                .append(spec.source()).append(".rows) ? null : ")
                .append(spec.source()).append(".rows[0]}\">\n");
        titleCluster(html, appHome, routeDir, spec);
        html.append("  <form id=\"").append(formId).append("\" method=\"post\" action=\"")
                .append(spec.action()).append("\"\n"
                        + "        hx-post=\"")
                .append(spec.action())
                .append("\" hx-target=\"#").append(formId).append("-errors\""
                        + " hx-swap=\"innerHTML\"\n"
                        + "        hx-disabled-elt=\"find button[type=submit]\""
                        + " hx-indicator=\"find .hc-spinner\">\n"
                        + "    <input type=\"hidden\" name=\"_csrf\" th:if=\"${_csrf != null}\""
                        + " th:value=\"${_csrf}\">\n"
                        + "    <div id=\"")
                .append(formId).append("-errors\"></div>\n"
                        + "    <div class=\"hc-stack\">\n");
        for (ViewFields.FieldDef field : fields) {
            field(html, field);
        }
        html.append("      <span class=\"hc-action\">\n"
                + "        <button type=\"submit\" class=\"hc-button\""
                + " data-variant=\"primary\" th:text=\"#{tql.view.submit}\">Save</button>\n"
                + "        <span class=\"hc-spinner htmx-indicator\" aria-hidden=\"true\"></span>\n");
        slot(html, appHome, routeDir, spec, "actions", "        ");
        html.append("      </span>\n"
                + "    </div>\n"
                + "  </form>\n");
        slot(html, appHome, routeDir, spec, "footer", "  ");
        html.append("</section>\n");
        return pageClose(html);
    }

    private static void field(StringBuilder html, ViewFields.FieldDef field) {
        String id = "field-" + field.name();
        String label = escape(field.labelFallback());
        if ("hidden".equals(field.widget())) {
            html.append("      <input type=\"hidden\" name=\"").append(field.name())
                    .append("\" th:value=\"${").append(prefill(field)).append("}\">\n");
            return;
        }
        html.append("      <div class=\"hc-field\">\n"
                + "        <label class=\"hc-field__label\" for=\"").append(id).append("\">")
                .append(label).append("</label>\n");
        switch (field.widget()) {
            case "checkbox" -> html.append("        <input type=\"hidden\" name=\"")
                    .append(field.name()).append("\" value=\"false\">\n"
                            + "        <input class=\"hc-checkbox\" id=\"")
                    .append(id)
                    .append("\" type=\"checkbox\" name=\"").append(field.name())
                    .append("\" value=\"true\">\n");
            case "select" -> {
                html.append("        <select class=\"hc-select\" id=\"").append(id)
                        .append("\" name=\"").append(field.name()).append("\"")
                        .append(field.required() ? " required" : "").append(">\n");
                if (field.codes() != null && !field.codes().isBlank()) {
                    // A catalog's options are runtime data, so the ejected template keeps
                    // reading them rather than freezing today's codes into the markup
                    // (docs/lookups.md, decision 8: ejection freezes the layout, not the
                    // behaviour).
                    html.append("          <option th:each=\"o : ${codes.")
                            .append(field.codes()).append(".options()}\"")
                            .append(" th:value=\"${o.key}\" th:text=\"${o.label}\"")
                            .append(" th:selected=\"${").append(prefill(field))
                            .append(" == o.key}\"></option>\n");
                } else {
                    for (String option : field.options()) {
                        html.append("          <option value=\"").append(escape(option))
                                .append("\" th:selected=\"${").append(prefill(field))
                                .append(" == '").append(expressionLiteral(option))
                                .append("'}\">")
                                .append(escape(option)).append("</option>\n");
                    }
                }
                html.append("        </select>\n");
            }
            case "textarea" -> html.append("        <textarea class=\"hc-input\" id=\"")
                    .append(id).append("\" name=\"").append(field.name()).append("\" rows=\"4\"")
                    .append(field.required() ? " required" : "")
                    .append(attr("maxlength", field.maxLength()))
                    .append(" th:text=\"${").append(prefill(field)).append("}\">")
                    .append("</textarea>\n");
            default -> html.append("        <input class=\"")
                    .append("date".equals(field.widget())
                            || "datetime-local".equals(field.widget())
                                    ? "hc-datepicker"
                                    : "hc-input")
                    .append("\" id=\"").append(id).append("\" type=\"").append(field.widget())
                    .append("\" name=\"").append(field.name()).append("\"")
                    .append(field.required() ? " required" : "")
                    .append(attr("maxlength", field.maxLength()))
                    .append(attr("min", field.min())).append(attr("max", field.max()))
                    .append(field.step() == null ? "" : " step=\"" + field.step() + "\"")
                    .append(" th:value=\"${").append(prefill(field)).append("}\">\n");
        }
        html.append("      </div>\n");
    }

    private static StringBuilder pageOpen(ViewSpec spec) {
        String title = spec.title() != null ? spec.title() : ViewFields.humanize(spec.id());
        StringBuilder html = new StringBuilder();
        html.append("<html xmlns:th=\"http://www.thymeleaf.org\"\n"
                + "      th:replace=\"~{tql/shell :: shell('")
                .append(expressionLiteral(title))
                .append("', ~{}, ~{}, ~{:: #page-content})}\">\n"
                        + "<div id=\"page-content\" class=\"hc-stack\">\n");
        return html;
    }

    private static String pageClose(StringBuilder html) {
        return html.append("</div>\n</html>\n").toString();
    }

    /** The h2 + header-slot cluster every ejected page opens with. */
    private static void titleCluster(StringBuilder html, Path appHome, Path routeDir,
            ViewSpec spec) {
        String title = spec.title() != null ? spec.title() : ViewFields.humanize(spec.id());
        html.append("  <div class=\"hc-cluster\">\n    <h2>").append(escape(title))
                .append("</h2>\n    <span class=\"hc-spacer\"></span>\n");
        slot(html, appHome, routeDir, spec, "header", "    ");
        html.append("  </div>\n");
    }

    /** Inlines a filled slot as a static fragment insert; absent slots emit nothing. */
    private static void slot(StringBuilder html, Path appHome, Path routeDir, ViewSpec spec,
            String name, String indent) {
        String ref = spec.slots().get(name);
        if (ref == null) {
            return;
        }
        int separator = ref.indexOf("::");
        require(separator > 0, "slot " + name + " must reference '<template>::<fragment>'");
        String template = ref.substring(0, separator).trim();
        String fragment = ref.substring(separator + 2).trim();
        html.append(indent).append("<th:block th:insert=\"~{")
                .append(resolveTemplate(appHome, routeDir, template)).append(" :: ")
                .append(fragment).append("}\"/>\n");
    }

    /** A slot template resolves colocated-first, then under templates/, app-home-confined. */
    private static String resolveTemplate(Path appHome, Path routeDir, String template) {
        io.tesseraql.core.files.ConfinedPath home = io.tesseraql.core.files.ConfinedPath
                .under(appHome);
        Path colocated = routeDir.toAbsolutePath().normalize().resolve(template).normalize();
        Path candidate = Files.isRegularFile(colocated)
                ? colocated
                : home.root().resolve("templates").resolve(template);
        Path file = home.confine(candidate).filter(Files::isRegularFile).orElse(null);
        require(file != null, "slot template does not resolve: " + template);
        return home.root().relativize(file).toString().replace('\\', '/');
    }

    private static void cell(StringBuilder html, ViewSpec.Column column,
            java.util.Map<String, String> codes, String indent) {
        cell(html, column, codes, "row", indent);
    }

    private static void childCell(StringBuilder html, ViewSpec.Column column,
            java.util.Map<String, String> codes, String indent) {
        cell(html, column, codes, "child", indent);
    }

    private static void cell(StringBuilder html, ViewSpec.Column column,
            java.util.Map<String, String> codes, String var, String indent) {
        String text = value(codes, column.name(), var);
        if (column.link() != null) {
            html.append(indent).append("<td class=\"hc-datagrid__cell\"><a th:href=\"|")
                    .append(linkTemplate(column.link(), var)).append("|\" th:text=\"${")
                    .append(text).append("}\">").append(column.name())
                    .append("</a></td>\n");
        } else {
            html.append(indent).append("<td class=\"hc-datagrid__cell\" th:text=\"${")
                    .append(text).append("}\">").append(column.name())
                    .append("</td>\n");
        }
    }

    /**
     * The expression a cell reads: the row value, or — where the column's {@code domain:} names
     * a catalog — that value resolved through the same {@code codes} object the pattern used
     * (docs/lookups.md, decision 8).
     *
     * <p>The resolution is emitted as a call, not as the labels of the day. Ejecting freezes
     * the layout, never the data: a code renamed next month renames on the ejected page too,
     * which is the same promise the ejected {@code <select>} makes about its options.
     */
    private static String value(java.util.Map<String, String> codes, String column, String var) {
        String catalog = codes.get(column);
        return catalog == null
                ? var + key(column)
                : "codes" + key(catalog) + ".of(" + var + key(column) + ")";
    }

    /**
     * Which of the view's columns and fields resolve their value through a catalog: the
     * {@code domain:} references that name a domain whose legal values are a catalog's codes.
     * Resolved at eject time so the emitted template names the catalog directly.
     */
    private static java.util.Map<String, String> catalogByColumn(Path appHome, String viewRef,
            ViewSpec spec) {
        java.util.Map<String, String> domains = new java.util.LinkedHashMap<>();
        java.util.stream.Stream.of(spec.columns().stream(),
                spec.children().stream().flatMap(child -> child.columns().stream()),
                spec.panels().stream().flatMap(panel -> panel.columns().stream()))
                .flatMap(stream -> stream)
                .filter(column -> column.domain() != null)
                .forEach(column -> domains.put(column.name(), column.domain()));
        spec.fields().stream().filter(field -> field.domain() != null)
                .forEach(field -> domains.put(field.name(), field.domain()));
        if (domains.isEmpty()) {
            return java.util.Map.of();
        }
        io.tesseraql.yaml.domain.FieldDomains declared = io.tesseraql.yaml.domain.FieldDomains
                .load(appHome);
        java.util.Map<String, String> catalogs = new java.util.LinkedHashMap<>();
        domains.forEach((column, domain) -> {
            String catalog = declared.require(domain, "view " + viewRef).codes();
            if (catalog != null && !catalog.isBlank()) {
                catalogs.put(column, catalog);
            }
        });
        return catalogs;
    }

    /** {@code /users?sel={name}} &rarr; {@code /users?sel=${row['name']}} inside a literal. */
    private static String linkTemplate(String link, String var) {
        Matcher matcher = LINK_PLACEHOLDER.matcher(link);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    "${" + var + key(matcher.group(1)) + "}"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String label(ViewSpec.Column column) {
        return column.label() != null ? column.label() : ViewFields.humanize(column.name());
    }

    /** The null-safe prefill expression an ejected field reads its current value from. */
    private static String prefill(ViewFields.FieldDef field) {
        String column = field.column() != null ? field.column() : field.name();
        return "row == null ? '' : row" + key(column) + "";
    }

    /**
     * A map key inside a generated OGNL expression. A single-quoted ONE-character string
     * is a char literal in OGNL ({@code map.get('n')} misses the String key), so 1-char
     * column names quote with {@code &quot;} entities instead.
     */
    private static String key(String name) {
        return name.length() == 1 ? "[&quot;" + name + "&quot;]" : "['" + name + "']";
    }

    private static String attr(String name, Object value) {
        return value == null ? "" : " " + name + "=\"" + value + "\"";
    }

    private static String escape(String text) {
        // The quote is escaped now too: this copy dropped it, which is unsafe the moment a
        // label lands inside a quoted attribute (docs/duplication-consolidation.md, camp. 4).
        return io.tesseraql.core.text.Escapes.html(text);
    }

    /**
     * A value spliced into a single-quoted OGNL string literal inside a double-quoted
     * attribute: the literal's own escapes first (the backslash, then the quote that would end
     * it), the attribute grammar over the result. {@link #escape} alone never touches the
     * apostrophe — which ends the OGNL literal, so {@code O'Brien} broke the ejected template
     * and let a view spec splice into the expression.
     */
    private static String expressionLiteral(String text) {
        return escape(text.replace("\\", "\\\\").replace("'", "\\'"));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new TqlException(ViewSpec.INVALID_VIEW, "Cannot eject: " + message);
        }
    }
}
