package io.tesseraql.studio;

import io.tesseraql.studio.StudioService.Explorer;
import io.tesseraql.studio.StudioService.JobSummary;
import io.tesseraql.studio.StudioService.PreviewResult;
import io.tesseraql.studio.StudioService.RouteSummary;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Template-ready views over the Studio backend (design ch. 16, 47): pure mappings from the
 * {@link StudioService} records to plain maps and lists, with source links pre-encoded, served to
 * the bundled studio app through the {@code studio.*} service providers.
 */
public final class StudioViews {

    private static final String SOURCE_URL = "/_tesseraql/studio/ui/source?path=";

    private StudioViews() {
    }

    /** The explorer page model: the app name, edit mode, and its routes and jobs. */
    public static Map<String, Object> explorer(Explorer explorer) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("appName", explorer.appName());
        model.put("readOnly", explorer.readOnly());
        model.put("editable", !explorer.readOnly());
        List<Map<String, Object>> routes = new ArrayList<>();
        for (RouteSummary route : explorer.routes()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", route.id());
            row.put("method", route.method());
            row.put("path", route.path());
            row.put("recipe", route.recipe());
            row.put("source", route.source());
            row.put("sourceUrl", sourceUrl(route.source()));
            routes.add(row);
        }
        model.put("routes", routes);
        model.put("hasRoutes", !routes.isEmpty());
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (JobSummary job : explorer.jobs()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", job.id());
            row.put("recipe", job.recipe());
            row.put("source", job.source());
            row.put("sourceUrl", sourceUrl(job.source()));
            jobs.add(row);
        }
        model.put("jobs", jobs);
        model.put("hasJobs", !jobs.isEmpty());
        return model;
    }

    /**
     * The source page model: the file path, its content ({@code content} is a draft when one
     * exists, otherwise the source), edit mode, and — for the editor — whether an unsaved draft is
     * being shown plus the on-disk {@code sourceContent} so the draft can be compared against it.
     */
    public static Map<String, Object> source(String path, String content, boolean readOnly,
            boolean hasDraft, String sourceContent) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("path", path);
        model.put("content", content);
        model.put("readOnly", readOnly);
        model.put("editable", !readOnly);
        model.put("hasDraft", hasDraft);
        model.put("sourceContent", sourceContent);
        if (hasDraft) {
            model.put("diff", diffLines(sourceContent == null ? "" : sourceContent,
                    content == null ? "" : content));
        }
        return model;
    }

    /**
     * The live-validation fragment model for a {@link PreviewResult}: the raw outcome plus two
     * derived flags so the template carries no string-matching logic — {@code ok} for a clean
     * success and {@code needsData} for a template that parses but needs real route data to render.
     */
    public static Map<String, Object> preview(PreviewResult result) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("valid", result.valid());
        model.put("kind", result.kind());
        model.put("result", result.result());
        model.put("error", result.error());
        boolean needsData = result.valid() && result.result() != null
                && result.result().contains("needs route data");
        model.put("needsData", needsData);
        model.put("ok", result.valid() && !needsData);
        return model;
    }

    private static String sourceUrl(String source) {
        return SOURCE_URL + URLEncoder.encode(source, StandardCharsets.UTF_8);
    }

    /**
     * Cap on either side's line count before the {@code O(n*m)} LCS is skipped (the compare panel
     * falls back to the saved source verbatim); Studio source files are far smaller in practice.
     */
    private static final int DIFF_MAX_LINES = 1500;

    /**
     * A unified line diff of {@code oldText} → {@code newText} as a template-ready line model for
     * the {@code hc-code data-mode="diff"} surface: each entry is a {@code state}
     * ({@code context}/{@code added}/{@code removed}), 1-based {@code oldNo}/{@code newNo} line
     * numbers (null on the side a line is absent from), and the line {@code text}. Unchanged lines
     * stay {@code context} (computed from an LCS). Returns {@code null} when either side exceeds
     * {@link #DIFF_MAX_LINES}, so the caller falls back to a plain source view.
     */
    private static List<Map<String, Object>> diffLines(String oldText, String newText) {
        String[] a = oldText.isEmpty() ? new String[0] : oldText.split("\n", -1);
        String[] b = newText.isEmpty() ? new String[0] : newText.split("\n", -1);
        if (a.length > DIFF_MAX_LINES || b.length > DIFF_MAX_LINES) {
            return null;
        }
        int n = a.length;
        int m = b.length;
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a[i].equals(b[j])
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a[i].equals(b[j])) {
                lines.add(diffLine("context", i + 1, j + 1, a[i]));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                lines.add(diffLine("removed", i + 1, null, a[i]));
                i++;
            } else {
                lines.add(diffLine("added", null, j + 1, b[j]));
                j++;
            }
        }
        while (i < n) {
            lines.add(diffLine("removed", i + 1, null, a[i++]));
        }
        while (j < m) {
            lines.add(diffLine("added", null, j + 1, b[j++]));
        }
        return lines;
    }

    private static Map<String, Object> diffLine(String state, Integer oldNo, Integer newNo,
            String text) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("state", state);
        line.put("oldNo", oldNo);
        line.put("newNo", newNo);
        line.put("text", text);
        return line;
    }
}
