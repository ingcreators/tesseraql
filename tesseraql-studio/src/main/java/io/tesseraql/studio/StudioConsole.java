package io.tesseraql.studio;

import io.tesseraql.core.template.HtmlTemplateEngine;
import io.tesseraql.studio.StudioService.Explorer;
import io.tesseraql.studio.StudioService.JobSummary;
import io.tesseraql.studio.StudioService.RouteSummary;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Renders the TesseraQL Studio explorer and source viewer as self-contained HTML pages (design
 * ch. 16). The markup is a valid Light DOM document with inlined styles and no external resources,
 * so it serves under a strict {@code default-src 'self'} content security policy, and all dynamic
 * values are HTML-escaped. The pages are read-only views over the existing Studio backend; draft
 * editing stays on the JSON API.
 */
public final class StudioConsole {

    private static final String UI = "/_tesseraql/studio/ui";

    private StudioConsole() {
    }

    /** Renders the explorer page listing the app's routes and jobs, each linking to its source. */
    public static String renderExplorer(Explorer explorer) {
        StringBuilder out = open("TesseraQL Studio");
        out.append("<header class=\"topbar\"><h1>TesseraQL Studio &middot; ")
                .append(escape(explorer.appName())).append("</h1>")
                .append(explorer.readOnly()
                        ? "<span class=\"badge ro\">read-only</span>"
                        : "<span class=\"badge rw\">editable</span>")
                .append("</header>\n<main>\n");

        out.append("<section><h2>Routes</h2>");
        if (explorer.routes().isEmpty()) {
            out.append("<p class=\"empty\">No routes defined.</p>");
        } else {
            out.append("<table><thead><tr><th>Id</th><th>Method</th><th>Path</th>")
                    .append("<th>Recipe</th><th>Source</th></tr></thead><tbody>");
            for (RouteSummary route : explorer.routes()) {
                out.append("<tr>")
                        .append(td(route.id()))
                        .append(td(route.method()))
                        .append(td(route.path()))
                        .append(td(route.recipe()))
                        .append(sourceCell(route.source()))
                        .append("</tr>");
            }
            out.append("</tbody></table>");
        }
        out.append("</section>\n");

        out.append("<section><h2>Jobs</h2>");
        if (explorer.jobs().isEmpty()) {
            out.append("<p class=\"empty\">No jobs defined.</p>");
        } else {
            out.append("<table><thead><tr><th>Id</th><th>Recipe</th><th>Source</th></tr></thead><tbody>");
            for (JobSummary job : explorer.jobs()) {
                out.append("<tr>")
                        .append(td(job.id()))
                        .append(td(job.recipe()))
                        .append(sourceCell(job.source()))
                        .append("</tr>");
            }
            out.append("</tbody></table>");
        }
        out.append("</section>\n</main>\n</body>\n</html>\n");
        return out.toString();
    }

    /** Renders the read-only source viewer page for a single file. */
    public static String renderSource(String path, String content, boolean readOnly) {
        return renderSource(path, content, readOnly, null);
    }

    /**
     * Renders the source page for a single file. In read-only mode the content is shown as static
     * text; otherwise it is rendered in an editor form that saves a draft and applies it (which
     * reloads the affected routes). {@code status} is an optional banner message (e.g. the outcome
     * of a save/apply).
     */
    public static String renderSource(String path, String content, boolean readOnly, String status) {
        StringBuilder out = open("TesseraQL Studio &middot; source");
        out.append("<header class=\"topbar\"><h1>")
                .append(escape(path)).append("</h1>")
                .append("<a class=\"back\" href=\"").append(UI).append("\">&larr; explorer</a>")
                .append("</header>\n<main>\n<section>");
        if (status != null && !status.isBlank()) {
            out.append("<p class=\"status\">").append(escape(status)).append("</p>");
        }
        if (readOnly) {
            out.append("<p class=\"empty\">Read-only mode &mdash; edit drafts via the Studio API.</p>");
            out.append("<pre class=\"source\">").append(escape(content)).append("</pre>");
        } else {
            String safePath = escape(path);
            out.append("<form method=\"post\" action=\"").append(UI).append("/save\">")
                    .append("<input type=\"hidden\" name=\"path\" value=\"").append(safePath)
                    .append("\">")
                    .append("<textarea name=\"content\" class=\"source\" rows=\"24\" spellcheck=\"false\">")
                    .append(escape(content)).append("</textarea>")
                    .append("<div class=\"toolbar\"><button type=\"submit\">Save draft</button></div>")
                    .append("</form>");
            out.append("<form method=\"post\" action=\"").append(UI).append("/apply\">")
                    .append("<input type=\"hidden\" name=\"path\" value=\"").append(safePath)
                    .append("\">")
                    .append("<div class=\"toolbar\"><button type=\"submit\" class=\"apply\">")
                    .append("Apply draft &amp; reload</button></div>")
                    .append("</form>");
        }
        out.append("</section>\n</main>\n</body>\n</html>\n");
        return out.toString();
    }

    private static String sourceCell(String source) {
        String href = UI + "/source?path=" + URLEncoder.encode(source, StandardCharsets.UTF_8);
        return "<td><a href=\"" + escape(href) + "\">" + escape(source) + "</a></td>";
    }

    private static StringBuilder open(String title) {
        return new StringBuilder(2048)
                .append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>").append(title).append("</title>\n")
                .append("<style>").append(styles()).append("</style>\n")
                .append("</head>\n<body>\n");
    }

    private static String td(String value) {
        return "<td>" + escape(value == null ? "-" : value) + "</td>";
    }

    private static String escape(String value) {
        return HtmlTemplateEngine.escape(value == null ? "" : value);
    }

    private static String styles() {
        return "*{box-sizing:border-box}"
                + "body{margin:0;font-family:system-ui,sans-serif;background:#0f172a;color:#e2e8f0}"
                + ".topbar{display:flex;align-items:center;justify-content:space-between;"
                + "padding:16px 24px;background:#1e293b;border-bottom:1px solid #334155}"
                + ".topbar h1{font-size:16px;margin:0}"
                + "main{padding:24px;max-width:1100px;margin:0 auto}"
                + "section{background:#1e293b;border:1px solid #334155;border-radius:8px;"
                + "padding:16px 20px;margin-bottom:20px}"
                + "h2{font-size:15px;margin:0 0 12px;color:#93c5fd}"
                + "table{width:100%;border-collapse:collapse;font-size:13px}"
                + "th,td{text-align:left;padding:6px 10px;border-bottom:1px solid #334155}"
                + "th{color:#94a3b8;font-weight:600}"
                + "a{color:#93c5fd}"
                + ".badge{padding:4px 12px;border-radius:999px;font-size:12px;font-weight:700}"
                + ".badge.ro{background:#9a3412;color:#ffedd5}"
                + ".badge.rw{background:#166534;color:#dcfce7}"
                + ".back{font-size:13px;text-decoration:none}"
                + ".empty{color:#94a3b8;font-style:italic;margin:0 0 12px}"
                + ".source{background:#0b1220;border:1px solid #334155;border-radius:6px;"
                + "padding:14px;overflow:auto;font-size:13px;white-space:pre-wrap;word-break:break-word}"
                + "textarea.source{width:100%;color:#e2e8f0;font-family:monospace;resize:vertical}"
                + ".toolbar{margin:10px 0 4px}"
                + "button{background:#2563eb;color:#fff;border:0;border-radius:6px;"
                + "padding:8px 16px;font-size:13px;cursor:pointer}"
                + "button.apply{background:#166534}"
                + ".status{background:#14532d;color:#dcfce7;border-radius:6px;padding:8px 12px;"
                + "margin:0 0 12px}";
    }
}
