package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lint rules for attachments (roadmap Phase 30, {@code TQL-ATTACH-34xx}). */
class AppLinterAttachmentTest {

    private static void writeAttachment(Path dir, String body) throws Exception {
        Files.createDirectories(dir.resolve("attachments"));
        Files.writeString(dir.resolve("attachments/invoice_files.yml"), body);
    }

    private static List<String> attachCodes(List<LintFinding> findings) {
        return findings.stream().map(LintFinding::code).filter(c -> c.startsWith("TQL-ATTACH"))
                .toList();
    }

    private static final String VALID = """
            version: tesseraql/v1
            id: invoice_files
            kind: attachment
            basePath: /invoices/{invoiceId}/files
            record: { entity: invoice, key: invoiceId }
            bucket: app-uploads
            limits:
              maxBytes: 25MB
              contentTypes: [application/pdf, image/png]
            """;

    @Test
    void wellFormedAttachmentProducesNoFindings(@TempDir Path dir) throws Exception {
        writeAttachment(dir, VALID);
        assertThat(attachCodes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void wrongKindIsAnError(@TempDir Path dir) throws Exception {
        writeAttachment(dir, VALID.replace("kind: attachment", "kind: route"));
        assertThat(attachCodes(new AppLinter().lint(dir))).contains("TQL-ATTACH-3401");
    }

    @Test
    void missingBasePathIsAnError(@TempDir Path dir) throws Exception {
        writeAttachment(dir, VALID.replace("basePath: /invoices/{invoiceId}/files\n", ""));
        assertThat(attachCodes(new AppLinter().lint(dir))).contains("TQL-ATTACH-3402");
    }

    @Test
    void missingRecordIsAnError(@TempDir Path dir) throws Exception {
        writeAttachment(dir, VALID.replace("record: { entity: invoice, key: invoiceId }\n", ""));
        assertThat(attachCodes(new AppLinter().lint(dir))).contains("TQL-ATTACH-3403");
    }

    @Test
    void basePathMissingRecordKeyIsAnError(@TempDir Path dir) throws Exception {
        writeAttachment(dir, VALID.replace("/invoices/{invoiceId}/files", "/invoices/files"));
        assertThat(attachCodes(new AppLinter().lint(dir))).contains("TQL-ATTACH-3404");
    }

    @Test
    void unparseableMaxBytesIsAnError(@TempDir Path dir) throws Exception {
        writeAttachment(dir, VALID.replace("maxBytes: 25MB", "maxBytes: huge"));
        assertThat(attachCodes(new AppLinter().lint(dir))).contains("TQL-ATTACH-3405");
    }

    @Test
    void missingLimitsIsAnError(@TempDir Path dir) throws Exception {
        writeAttachment(dir, """
                version: tesseraql/v1
                id: invoice_files
                kind: attachment
                basePath: /invoices/{invoiceId}/files
                record: { entity: invoice, key: invoiceId }
                bucket: app-uploads
                """);
        assertThat(attachCodes(new AppLinter().lint(dir))).contains("TQL-ATTACH-3405");
    }
}
