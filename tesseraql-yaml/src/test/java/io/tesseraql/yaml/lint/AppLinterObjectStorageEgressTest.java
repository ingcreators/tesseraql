package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The object-storage egress allow-list (TQL-SEC-4110), which had no behavioural test at all.
 *
 * <p>{@code docs/attachments.md} and {@code docs/security-hardening.md} both advertise this as
 * deny-by-default, and the only thing in the test tree naming the code was a set-membership entry
 * in a status-mapping ledger. So the rule deciding which buckets an application may write to was
 * enforced by review.
 *
 * <p>The gap these tests found: the rule walked attachments and nothing else, while
 * {@code tesseraql.temp.store: blob} writes every produced file and every spooled result to
 * {@code tesseraql.temp.bucket} — which defaults to a name nobody types and so nobody allow-lists.
 *
 * <p>The YAML here is assembled from explicitly indented lines rather than nested text blocks. A
 * text block's incidental-whitespace stripping is relative to its own closing delimiter, so a
 * block spliced into another one lands at whatever indent that arithmetic produces — which first
 * put {@code object-storage:} underneath a datasource, where the rule correctly found no provider
 * and reported nothing.
 */
class AppLinterObjectStorageEgressTest {

    /** Everything under {@code tesseraql:}; each element is one already-indented line. */
    private static void writeConfig(Path dir, String... underTesseraql) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        StringBuilder yaml = new StringBuilder("tesseraql:\n"
                + "  app:\n"
                + "    name: t\n"
                + "  datasources:\n"
                + "    main:\n"
                + "      jdbcUrl: jdbc:postgresql://localhost/main\n");
        for (String line : underTesseraql) {
            yaml.append(line).append('\n');
        }
        Files.writeString(dir.resolve("config/tesseraql.yml"), yaml.toString());
    }

    private static final String[] S3_ALLOWING_UPLOADS = {
            "  object-storage:",
            "    provider: s3",
            "    allowedBuckets:",
            "      - acme-uploads",
            "    buckets:",
            "      uploads:",
            "        bucket: acme-uploads",
    };

    private static String[] with(String[] head, String... more) {
        String[] all = java.util.Arrays.copyOf(head, head.length + more.length);
        System.arraycopy(more, 0, all, head.length, more.length);
        return all;
    }

    @Test
    void anAttachmentOutsideTheAllowListIsRefused(@TempDir Path dir) throws Exception {
        writeConfig(dir, S3_ALLOWING_UPLOADS);
        writeAttachment(dir, "acme-elsewhere");

        assertThat(new AppLinter().lint(dir))
                .anyMatch(f -> f.code().equals("TQL-SEC-4110") && f.isError()
                        && f.message().contains("acme-elsewhere"));
    }

    @Test
    void anAttachmentInsideTheAllowListPasses(@TempDir Path dir) throws Exception {
        writeConfig(dir, S3_ALLOWING_UPLOADS);
        writeAttachment(dir, "uploads");

        assertThat(new AppLinter().lint(dir))
                .noneMatch(f -> f.code().equals("TQL-SEC-4110"));
    }

    /**
     * The reach gap. Nothing here declares a bucket, so nothing reads as suspicious — and the app
     * boots and dies on its first export, because the blob temp store writes to a default bucket
     * the allow-list never heard of.
     */
    @Test
    void aBlobTempStoreOutsideTheAllowListIsRefused(@TempDir Path dir) throws Exception {
        writeConfig(dir, with(S3_ALLOWING_UPLOADS, "  temp:", "    store: blob"));

        assertThat(new AppLinter().lint(dir))
                .anyMatch(f -> f.code().equals("TQL-SEC-4110") && f.isError()
                        && f.message().contains("tesseraql-temp"));
    }

    @Test
    void aBlobTempStoreInsideTheAllowListPasses(@TempDir Path dir) throws Exception {
        writeConfig(dir,
                "  object-storage:",
                "    provider: s3",
                "    allowedBuckets:",
                "      - acme-scratch",
                "  temp:",
                "    store: blob",
                "    bucket: acme-scratch");

        assertThat(new AppLinter().lint(dir))
                .noneMatch(f -> f.code().equals("TQL-SEC-4110"));
    }

    /** A bucket alias resolves before the membership check, for the temp store as for an
     * attachment. */
    @Test
    void aBlobTempStoreResolvesItsAliasBeforeTheCheck(@TempDir Path dir) throws Exception {
        writeConfig(dir,
                "  object-storage:",
                "    provider: s3",
                "    allowedBuckets:",
                "      - acme-scratch",
                "    buckets:",
                "      scratch:",
                "        bucket: acme-scratch",
                "  temp:",
                "    store: blob",
                "    bucket: scratch");

        assertThat(new AppLinter().lint(dir))
                .noneMatch(f -> f.code().equals("TQL-SEC-4110"));
    }

    /** The file provider needs no allow-list, so nothing here is checked at all. */
    @Test
    void theFileProviderIsNotChecked(@TempDir Path dir) throws Exception {
        writeConfig(dir,
                "  object-storage:",
                "    provider: file",
                "  temp:",
                "    store: blob");

        assertThat(new AppLinter().lint(dir))
                .noneMatch(f -> f.code().equals("TQL-SEC-4110"));
    }

    private static void writeAttachment(Path dir, String bucket) throws Exception {
        Path attachments = dir.resolve("attachments");
        Files.createDirectories(attachments);
        Files.writeString(attachments.resolve("invoice.yml"), """
                version: tesseraql/v1
                id: invoice
                kind: attachment
                bucket: %s
                """.formatted(bucket));
    }
}
