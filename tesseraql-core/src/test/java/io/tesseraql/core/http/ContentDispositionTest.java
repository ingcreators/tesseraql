package io.tesseraql.core.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The one filename sanitizer. The backslash case is the one three of the four former call sites
 * missed: {@code report.pdf\} escaped the closing quote and left the quoted-string
 * unterminated, which download parsers resolve differently — reachable from a client-supplied
 * upload filename on the attachment surface.
 */
class ContentDispositionTest {

    @Test
    void everyQuotedStringBreakerIsReplaced() {
        assertThat(ContentDisposition.sanitizeFilename("report.pdf\\"))
                .isEqualTo("report.pdf_");
        assertThat(ContentDisposition.sanitizeFilename("a\"b\r\nc"))
                .isEqualTo("a_b__c");
        assertThat(ContentDisposition.sanitizeFilename("月次レポート.csv"))
                .isEqualTo("月次レポート.csv");
    }

    @Test
    void theAttachmentValueIsWholeAndQuoted() {
        assertThat(ContentDisposition.attachment("orders\".csv"))
                .isEqualTo("attachment; filename=\"orders_.csv\"");
    }
}
