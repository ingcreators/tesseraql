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

    private static final Pattern LINK_PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    private ViewEjector() {
    }

    /**
     * Generates the ejected template for a view. {@code fields} carries a form view's derived
     * definitions (empty for list/detail); {@code targetPath} is the app-home-relative path the
     * template will live at (drives the checksum stamp).
     */
    public static ScaffoldedFile eject(Path appHome, Path routeDir, String viewRef,
            ViewSpec spec, List<ViewFields.FieldDef> fields, String targetPath) {
        String body = switch (spec.view()) {
            case ViewSpec.LIST -> list(appHome, routeDir, spec);
            case ViewSpec.DETAIL -> detail(appHome, routeDir, spec);
            case ViewSpec.FORM -> form(appHome, routeDir, spec, fields);
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
        Pattern line = Pattern.compile("(?m)^(\\s*)view:\\s*" + Pattern.quote(viewRef)
                + "\\s*$");
        Matcher matcher = line.matcher(routeYaml);
        if (!matcher.find()) {
            throw new TqlException(ViewSpec.INVALID_VIEW, "The route does not declare 'view: "
                    + viewRef + "' — cannot flip it to template:");
        }
        return matcher.replaceFirst("$1template: " + Matcher.quoteReplacement(templateName));
    }

    private static String list(Path appHome, Path routeDir, ViewSpec spec) {
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
            cell(html, column, "            ");
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

    private static String detail(Path appHome, Path routeDir, ViewSpec spec) {
        require(!spec.fields().isEmpty(),
                "a detail view needs explicit fields: before ejecting — the template pins them");
        for (ViewSpec.Child child : spec.children()) {
            require(!child.columns().isEmpty(), "child " + child.source()
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
                            + "      <span th:text=\"${row == null ? '' : row['")
                    .append(field.name()).append("']}\">").append(field.name())
                    .append("</span>\n"
                            + "    </div>\n");
        }
        html.append("  </div>\n");
        for (ViewSpec.Child child : spec.children()) {
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
                childCell(html, column, "              ");
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

    private static String form(Path appHome, Path routeDir, ViewSpec spec,
            List<ViewFields.FieldDef> fields) {
        require(fields != null && !fields.isEmpty(),
                "a form view ejects from its derived fields — the action route declares none");
        String formId = spec.id().replace('.', '-') + "-form";
        StringBuilder html = pageOpen(spec);
        html.append("<section class=\"hc-card\">\n");
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
                    .append("\">\n");
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
                for (String option : field.options()) {
                    html.append("          <option value=\"").append(escape(option))
                            .append("\">").append(escape(option)).append("</option>\n");
                }
                html.append("        </select>\n");
            }
            case "textarea" -> html.append("        <textarea class=\"hc-input\" id=\"")
                    .append(id).append("\" name=\"").append(field.name()).append("\" rows=\"4\"")
                    .append(field.required() ? " required" : "")
                    .append(attr("maxlength", field.maxLength())).append("></textarea>\n");
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
                    .append(">\n");
        }
        html.append("      </div>\n");
    }

    private static StringBuilder pageOpen(ViewSpec spec) {
        String title = spec.title() != null ? spec.title() : ViewFields.humanize(spec.id());
        StringBuilder html = new StringBuilder();
        html.append("<html xmlns:th=\"http://www.thymeleaf.org\"\n"
                + "      th:replace=\"~{tql/shell :: shell('")
                .append(title.replace("'", "\\'"))
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
        Path home = appHome.toAbsolutePath().normalize();
        Path colocated = routeDir.toAbsolutePath().normalize().resolve(template).normalize();
        Path file = Files.isRegularFile(colocated)
                ? colocated
                : home.resolve("templates").resolve(template).normalize();
        require(file.startsWith(home) && Files.isRegularFile(file),
                "slot template does not resolve: " + template);
        return home.relativize(file).toString().replace('\\', '/');
    }

    private static void cell(StringBuilder html, ViewSpec.Column column, String indent) {
        if (column.link() != null) {
            html.append(indent).append("<td class=\"hc-datagrid__cell\"><a th:href=\"|")
                    .append(linkTemplate(column.link(), "row")).append("|\" th:text=\"${row['")
                    .append(column.name()).append("']}\">").append(column.name())
                    .append("</a></td>\n");
        } else {
            html.append(indent).append("<td class=\"hc-datagrid__cell\" th:text=\"${row['")
                    .append(column.name()).append("']}\">").append(column.name())
                    .append("</td>\n");
        }
    }

    private static void childCell(StringBuilder html, ViewSpec.Column column, String indent) {
        if (column.link() != null) {
            html.append(indent).append("<td class=\"hc-datagrid__cell\"><a th:href=\"|")
                    .append(linkTemplate(column.link(), "child")).append("|\" th:text=\"${child['")
                    .append(column.name()).append("']}\">").append(column.name())
                    .append("</a></td>\n");
        } else {
            html.append(indent).append("<td class=\"hc-datagrid__cell\" th:text=\"${child['")
                    .append(column.name()).append("']}\">").append(column.name())
                    .append("</td>\n");
        }
    }

    /** {@code /users?sel={name}} &rarr; {@code /users?sel=${row['name']}} inside a literal. */
    private static String linkTemplate(String link, String var) {
        Matcher matcher = LINK_PLACEHOLDER.matcher(link);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    "${" + var + "['" + matcher.group(1) + "']}"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String label(ViewSpec.Column column) {
        return column.label() != null ? column.label() : ViewFields.humanize(column.name());
    }

    private static String attr(String name, Integer value) {
        return value == null ? "" : " " + name + "=\"" + value + "\"";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new TqlException(ViewSpec.INVALID_VIEW, "Cannot eject: " + message);
        }
    }
}
