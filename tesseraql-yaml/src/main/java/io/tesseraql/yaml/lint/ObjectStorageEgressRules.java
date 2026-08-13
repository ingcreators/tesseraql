package io.tesseraql.yaml.lint;

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

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        lintObjectStorageEgress(context.appHome(), manifest, findings);
    }

    /**
     * Object-storage egress (roadmap Phase 30 slice 2): when {@code provider: s3}, every attachment's
     * resolved bucket must be in {@code tesseraql.object-storage.allowedBuckets} (deny-by-default,
     * mirroring the HTTP/poll egress allow-lists). The {@code file} provider needs no allow-list.
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
        for (io.tesseraql.yaml.manifest.AttachmentFile attachment : manifest.attachments()) {
            io.tesseraql.yaml.model.AttachmentDefinition def = attachment.definition();
            String source = LintSupport.relative(appHome, attachment.source());
            String logical = def.bucket();
            if (logical == null || logical.isBlank()) {
                findings.add(new LintFinding("TQL-SEC-4110", "error", source, "attachment '"
                        + def.id()
                        + "' must declare a bucket when tesseraql.object-storage.provider"
                        + " is s3"));
                continue;
            }
            String real = config.getString(
                    "tesseraql.object-storage.buckets." + logical + ".bucket").orElse(logical);
            if (!allowed.contains(real)) {
                findings.add(new LintFinding("TQL-SEC-4110", "error", source, "attachment '"
                        + def.id() + "' targets bucket '" + real + "' which is not in "
                        + "tesseraql.object-storage.allowedBuckets (deny by default)"));
            }
        }
    }
}
