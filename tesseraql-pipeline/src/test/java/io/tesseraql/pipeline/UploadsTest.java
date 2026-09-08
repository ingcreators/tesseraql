package io.tesseraql.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which part of a multipart request is the uploaded file.
 *
 * <p>Additive coverage: the rule is right and untested. Its <em>fallback</em> half —
 * {@code attachments.values().iterator().next()} — has never executed anywhere in the suite,
 * because every multipart body in the tree names its file part {@code file}. All three callers
 * document the fallback as contract, and dropping it would silently feed a whole MIME envelope to
 * the CSV importer for a client posting {@code -F "csv=@items.csv"}.
 *
 * <p>Determinism comes free: {@code Request.attachments()} returns the live {@code LinkedHashMap},
 * so insertion order is the declaration order the fallback picks from.
 */
class UploadsTest {

    private static Exchange multipart(String contentType) {
        Exchange exchange = new Exchange(Beans.NONE);
        exchange.request().header(Headers.CONTENT_TYPE, contentType);
        return exchange;
    }

    private static Part file(Path dir, String name) throws IOException {
        Path path = dir.resolve(name);
        Files.writeString(path, "id,name\n1,a\n");
        return Part.of(path, "text/csv", name);
    }

    @Test
    void theFileNamedPartWinsOverTheOthers(@TempDir Path dir) throws IOException {
        Exchange exchange = multipart("multipart/form-data; boundary=x");
        exchange.request().attachments().put("csv", file(dir, "first.csv"));
        exchange.request().attachments().put("file", file(dir, "chosen.csv"));
        exchange.request().attachments().put("extra", file(dir, "last.csv"));

        assertThat(Uploads.filePart(exchange)).get()
                .extracting(Part::filename)
                .isEqualTo("chosen.csv");
    }

    /**
     * The half nothing else exercises. A client posting {@code -F "csv=@items.csv"} names no part
     * {@code file}, and the first declared part is what it meant.
     */
    @Test
    void theFirstPartIsTheFallbackWhenNothingIsNamedFile(@TempDir Path dir) throws IOException {
        Exchange exchange = multipart("multipart/form-data; boundary=x");
        exchange.request().attachments().put("csv", file(dir, "first.csv"));
        exchange.request().attachments().put("notes", file(dir, "second.csv"));

        assertThat(Uploads.filePart(exchange)).get()
                .extracting(Part::filename)
                .isEqualTo("first.csv");
    }

    @Test
    void aMultipartRequestWithNoPartsIsEmpty() {
        assertThat(Uploads.filePart(multipart("multipart/form-data; boundary=x"))).isEmpty();
    }

    @Test
    void aRequestThatIsNotMultipartIsEmpty(@TempDir Path dir) throws IOException {
        Exchange exchange = multipart("application/json");
        exchange.request().attachments().put("file", file(dir, "ignored.csv"));

        assertThat(Uploads.filePart(exchange))
                .as("absence is the caller's to interpret — the importer falls back to the raw"
                        + " body, the deploy endpoint refuses")
                .isEmpty();
    }

    @Test
    void aRequestWithNoContentTypeAtAllIsNotMultipart() {
        assertThat(Uploads.isMultipart(new Exchange(Beans.NONE))).isFalse();
    }

    /**
     * RFC 2045 media types are case-insensitive, and {@code isMultipart} lower-cases before
     * matching. Nothing in the tree sends anything but lower case, which is exactly why the
     * deliberate {@code toLowerCase} is worth pinning.
     */
    @Test
    void theContentTypeIsMatchedCaseInsensitively(@TempDir Path dir) throws IOException {
        Exchange exchange = multipart("MULTIPART/FORM-DATA; boundary=x");
        exchange.request().attachments().put("csv", file(dir, "shouty.csv"));

        assertThat(Uploads.isMultipart(exchange)).isTrue();
        assertThat(Uploads.filePart(exchange)).get()
                .extracting(Part::filename)
                .isEqualTo("shouty.csv");
    }
}
