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
}
