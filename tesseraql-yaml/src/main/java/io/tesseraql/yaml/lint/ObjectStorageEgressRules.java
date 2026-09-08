package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;

import io.tesseraql.yaml.manifest.AppManifest;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Object-storage egress against the configured allow-list.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class ObjectStorageEgressRules implements LintRule {

    private static final String INVALID_OBJECT_STORAGE_BUCKET = "TQL-SEC-4110";

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        lintObjectStorageEgress(context.appHome(), manifest, findings);
    }

    /**
     * Object-storage egress (roadmap Phase 30 slice 2): when {@code provider: s3}, every bucket the
     * runtime will write to must be in {@code tesseraql.object-storage.allowedBuckets}
     * (deny-by-default, mirroring the HTTP/poll egress allow-lists). The {@code file} provider
     * needs no allow-list.
     *
     * <p>Every bucket, not every attachment. {@code tesseraql.temp.store: blob} builds a second
     * writer over {@code tesseraql.temp.bucket} — defaulting to {@code tesseraql-temp}, a name
     * nobody types and so nobody thinks to allow-list. Checking only attachments let that app lint
     * clean, boot, and fail on its first export with a runtime refusal rendered as a 500. The
     * control is fail-closed either way; the defect was that a build-time answer arrived at
     * request time.
     */
    void lintObjectStorageEgress(Path appHome, AppManifest manifest,
            List<LintFinding> findings) {
        io.tesseraql.yaml.config.AppConfig config = manifest.config();
        String provider = config.getString("tesseraql.object-storage.provider").orElse("file");
        if (!"s3".equalsIgnoreCase(provider)) {
            return;
        }
        Set<String> allowed = new HashSet<>();
        if (config
                .navigate("tesseraql.object-storage.allowedBuckets") instanceof List<?> declared) {
            declared.forEach(value -> allowed.add(String.valueOf(value)));
        }
        if ("blob".equalsIgnoreCase(config.getString("tesseraql.temp.store").orElse("file"))) {
            String logical = config.getString("tesseraql.temp.bucket").orElse("tesseraql-temp");
            String real = resolve(config, logical);
            if (!allowed.contains(real)) {
                findings.add(new LintFinding(INVALID_OBJECT_STORAGE_BUCKET, ERROR,
                        "config/tesseraql.yml",
                        "tesseraql.temp.store is blob and its bucket '" + real + "' is not in"
                                + " tesseraql.object-storage.allowedBuckets (deny by default);"
                                + " every produced file and every spooled result goes there"));
            }
        }
        for (io.tesseraql.yaml.manifest.AttachmentFile attachment : manifest.attachments()) {
            io.tesseraql.yaml.model.AttachmentDefinition def = attachment.definition();
            String source = LintSupport.relative(appHome, attachment.source());
            String logical = def.bucket();
            if (logical == null || logical.isBlank()) {
                findings.add(new LintFinding(INVALID_OBJECT_STORAGE_BUCKET, ERROR, source,
                        "attachment '"
                                + def.id()
                                + "' must declare a bucket when tesseraql.object-storage.provider"
                                + " is s3"));
                continue;
            }
            String real = resolve(config, logical);
            if (!allowed.contains(real)) {
                findings.add(new LintFinding(INVALID_OBJECT_STORAGE_BUCKET, ERROR, source,
                        "attachment '"
                                + def.id() + "' targets bucket '" + real + "' which is not in "
                                + "tesseraql.object-storage.allowedBuckets (deny by default)"));
            }
        }
    }

    /** A declared bucket alias to the bucket the store actually addresses. */
    private static String resolve(io.tesseraql.yaml.config.AppConfig config, String logical) {
        return config.getString("tesseraql.object-storage.buckets." + logical + ".bucket")
                .orElse(logical);
    }
}
