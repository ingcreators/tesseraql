package io.tesseraql.yaml.lint;

import io.tesseraql.yaml.manifest.AppManifest;
import java.nio.file.Path;
import java.util.List;

/**
 * Attachment definitions.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class AttachmentRules implements LintRule {

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        lintAttachments(context.appHome(), manifest, findings);
    }

    void lintAttachments(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        for (io.tesseraql.yaml.manifest.AttachmentFile attachment : manifest.attachments()) {
            lintAttachmentDefinition(appHome, attachment, findings);
        }
    }

    /** Checks an attachment definition: kind, base path, owning record, and upload limits. */
    private void lintAttachmentDefinition(Path appHome,
            io.tesseraql.yaml.manifest.AttachmentFile attachment, List<LintFinding> findings) {
        String source = LintSupport.relative(appHome, attachment.source());
        io.tesseraql.yaml.model.AttachmentDefinition def = attachment.definition();
        String id = def.id();
        if (!"attachment".equals(def.kind())) {
            findings.add(new LintFinding("TQL-ATTACH-3401", "error", source,
                    "attachment '" + id + "' must declare kind: attachment"));
        }
        boolean hasBasePath = def.basePath() != null && !def.basePath().isBlank();
        if (!hasBasePath) {
            findings.add(new LintFinding("TQL-ATTACH-3402", "error", source,
                    "attachment '" + id + "' must declare a basePath"));
        }
        io.tesseraql.yaml.model.AttachmentDefinition.RecordSpec record = def.record();
        boolean hasEntity = record != null && record.entity() != null
                && !record.entity().isBlank();
        boolean hasKey = record != null && record.key() != null && !record.key().isBlank();
        if (!hasEntity || !hasKey) {
            findings.add(new LintFinding("TQL-ATTACH-3403", "error", source,
                    "attachment '" + id + "' must declare record.entity and record.key"));
        } else if (hasBasePath && !def.basePath().contains("{" + record.key() + "}")) {
            findings.add(new LintFinding("TQL-ATTACH-3404", "error", source,
                    "attachment '" + id + "' basePath must contain the record key '{"
                            + record.key() + "}' as a path parameter"));
        }
        io.tesseraql.yaml.model.AttachmentDefinition.Limits limits = def.limits();
        if (limits == null || limits.maxBytesValue() <= 0) {
            findings.add(new LintFinding("TQL-ATTACH-3405", "error", source,
                    "attachment '" + id + "' must declare a positive limits.maxBytes (e.g. 25MB)"));
        }
    }
}
