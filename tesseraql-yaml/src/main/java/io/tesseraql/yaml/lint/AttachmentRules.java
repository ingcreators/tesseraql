package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;

import io.tesseraql.yaml.manifest.AppManifest;
import java.nio.file.Path;
import java.util.List;

/**
 * Attachment definitions.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class AttachmentRules implements LintRule {

    private static final String ATTACHMENT_KIND_MISSING = "TQL-ATTACH-3401";

    private static final String ATTACHMENT_BASE_PATH_MISSING = "TQL-ATTACH-3402";

    private static final String ATTACHMENT_RECORD_MISSING = "TQL-ATTACH-3403";

    private static final String ATTACHMENT_BASE_PATH_WITHOUT_KEY = "TQL-ATTACH-3404";

    private static final String ATTACHMENT_MAX_BYTES_MISSING = "TQL-ATTACH-3405";

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
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
            findings.add(new LintFinding(ATTACHMENT_KIND_MISSING, ERROR, source,
                    "attachment '" + id + "' must declare kind: attachment"));
        }
        boolean hasBasePath = def.basePath() != null && !def.basePath().isBlank();
        if (!hasBasePath) {
            findings.add(new LintFinding(ATTACHMENT_BASE_PATH_MISSING, ERROR, source,
                    "attachment '" + id + "' must declare a basePath"));
        }
        io.tesseraql.yaml.model.AttachmentDefinition.RecordSpec record = def.record();
        boolean hasEntity = record != null && record.entity() != null
                && !record.entity().isBlank();
        boolean hasKey = record != null && record.key() != null && !record.key().isBlank();
        if (!hasEntity || !hasKey) {
            findings.add(new LintFinding(ATTACHMENT_RECORD_MISSING, ERROR, source,
                    "attachment '" + id + "' must declare record.entity and record.key"));
        } else if (hasBasePath && !def.basePath().contains("{" + record.key() + "}")) {
            findings.add(new LintFinding(ATTACHMENT_BASE_PATH_WITHOUT_KEY, ERROR, source,
                    "attachment '" + id + "' basePath must contain the record key '{"
                            + record.key() + "}' as a path parameter"));
        }
        io.tesseraql.yaml.model.AttachmentDefinition.Limits limits = def.limits();
        if (limits == null || limits.maxBytesValue() <= 0) {
            findings.add(new LintFinding(ATTACHMENT_MAX_BYTES_MISSING, ERROR, source,
                    "attachment '" + id + "' must declare a positive limits.maxBytes (e.g. 25MB)"));
        }
    }
}
