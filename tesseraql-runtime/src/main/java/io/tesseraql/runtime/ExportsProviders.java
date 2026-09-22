package io.tesseraql.runtime;

import io.tesseraql.compiler.binding.TransferCards;
import io.tesseraql.core.files.FileTransferService;
import io.tesseraql.core.http.BasePaths;
import io.tesseraql.core.http.PercentEncoding;
import io.tesseraql.core.service.ServiceProviders;
import io.tesseraql.yaml.i18n.MessageCatalog;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.RouteFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The bundled "My exports" page's provider (docs/job-inbox.md decisions 4 and 5): the caller's
 * own exports of this application, newest first, each as the job card the status poll answers
 * with, its URLs the route's own subtree under the prefix the request handed over.
 *
 * <p>The subject and the tenant are whatever the route mapped — {@code principal.subject} and
 * {@code tenant.id}, the only way the page can ask — so the page can only ever list the
 * caller's own, the trust the tasks and inbox providers extend to their routes. Who may read a
 * transfer stays the route's decision: a card polls, downloads and cancels through the route's
 * subtree, which answers for its own and calls the rest unknown.
 */
final class ExportsProviders {

    /** The page's cap: 50 rows plus the one that says there are more (the console's number). */
    private static final int CAP = 50;

    private ExportsProviders() {
    }

    static void register(ServiceProviders providers, FileTransferService transfers,
            AppManifest manifest, String appName, Path appHome) {
        providers.register("exports.mine",
                params -> mine(params, transfers, manifest, appName, appHome));
    }

    static Map<String, Object> mine(Map<String, Object> params, FileTransferService transfers,
            AppManifest manifest, String appName, Path appHome) {
        Map<String, Object> model = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        if (transfers != null) {
            String subject = text(params.get("subject"));
            String tenant = text(params.get("tenant"));
            String base = params.get("base") == null ? "" : String.valueOf(params.get("base"));
            Locale locale = params.get("locale") == null
                    ? Locale.ROOT
                    : Locale.forLanguageTag(String.valueOf(params.get("locale")));
            MessageCatalog catalog = TransferCards.catalog(appHome);
            Map<String, String> paths = exportPaths(manifest);
            List<FileTransferService.TransferStatus> own = transfers.mine(appName, subject,
                    tenant, "EXPORT", CAP + 1);
            truncated = own.size() > CAP;
            for (FileTransferService.TransferStatus status : own.subList(0,
                    Math.min(CAP, own.size()))) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("filename", status.filename() == null ? "" : status.filename());
                row.put("route", status.routeId());
                row.put("createdAt", status.createdAt());
                String path = paths.get(status.routeId());
                // A route the manifest no longer declares has no subtree to poll, download or
                // cancel through: the row renders its heading and no card (decision 5).
                row.put("card", path == null
                        ? null
                        : TransferCards.ofExport(status,
                                wire(base, path + "/" + status.transferId()),
                                wire(base, path + "/" + status.transferId() + "/cancel"),
                                catalog, locale));
                rows.add(row);
            }
        }
        model.put("enabled", transfers != null);
        model.put("rows", rows);
        model.put("count", rows.size());
        model.put("truncated", truncated);
        return model;
    }

    /**
     * The wire form of a base-relative path: the prefix joined on, then the whole reference
     * encoded once — {@code BasePath.url}'s rule, with the prefix handed over as a param
     * because a provider holds no exchange (docs/base-path-emission.md decision 1).
     */
    private static String wire(String base, String path) {
        return PercentEncoding.uriLiteral(BasePaths.join(base, path));
    }

    /** Every {@code file-export} route's URL path by id — the subtrees the cards poll. */
    private static Map<String, String> exportPaths(AppManifest manifest) {
        Map<String, String> byId = new LinkedHashMap<>();
        if (manifest == null) {
            return byId;
        }
        for (RouteFile route : manifest.routes()) {
            if (route.definition().fileExport() != null) {
                byId.putIfAbsent(route.definition().id(), route.urlPath());
            }
        }
        return byId;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }
}
