package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.FileReadSpec;
import io.tesseraql.core.files.FileTransferService;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Accepts an uploaded file body (design ch. 28): the raw request body, or a multipart file part,
 * is the file content.
 *
 * <p>What happens next is the import's own declaration. Without {@code review:} the upload starts
 * the import and answers 202 with the transfer id and status URL, as it always has. With
 * {@code review: required} nothing is written: the file is parsed and validated, the batch is
 * parked, and the answer is the report — 200 with a confirm token when a committable set exists,
 * 422 without one when it does not (docs/csv-import.md decision 1).
 */
public final class FileImportProcessor implements Step {

    private static final TqlErrorCode EMPTY_BODY = new TqlErrorCode(TqlDomain.LD, 2820);
    private static final TqlErrorCode NO_SERVICE = new TqlErrorCode(TqlDomain.LD, 2821);

    private final String routeId;
    private final String urlPath;
    private final String appName;
    private final String format;
    private final FileReadSpec readSpec;
    private final String localeDeclaration;
    private final Path rowSqlFile;
    private final String onError;
    private final boolean review;
    private final Map<String, io.tesseraql.yaml.model.InputField> input;

    public FileImportProcessor(String routeId, String urlPath, String appName, String format,
            FileReadSpec readSpec, String localeDeclaration, Path rowSqlFile, String onError,
            boolean review, Map<String, io.tesseraql.yaml.model.InputField> input) {
        this.routeId = routeId;
        this.urlPath = urlPath;
        this.appName = appName;
        this.format = format;
        this.readSpec = readSpec;
        this.localeDeclaration = localeDeclaration;
        this.rowSqlFile = rowSqlFile;
        this.onError = onError;
        this.review = review;
        this.input = input == null ? Map.of() : Map.copyOf(input);
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        FileTransferService transfers = exchange.beans().lookup(
                TesseraqlProperties.FILE_TRANSFER_BEAN,
                FileTransferService.class);
        if (transfers == null) {
            throw new TqlException(NO_SERVICE, "File transfer service is not configured");
        }
        try (InputStream content = body(exchange)) {
            if (content == null) {
                throw new TqlException(EMPTY_BODY,
                        "file-import expects the uploaded file as the request body");
            }
            // The service spools the stream off-heap before returning; large uploads never
            // materialize in memory here (an empty upload fails with the same 400).
            FileTransferService.ImportRequest request = new FileTransferService.ImportRequest(
                    routeId, appName, format,
                    readSpec.withLocale(FormatSources.resolve(exchange, localeDeclaration)),
                    rowSqlFile, onError, ImportContracts.of(exchange, input));
            if (review) {
                respondReview(exchange, transfers.reviewImport(request, subject(exchange),
                        content));
                return;
            }
            respondAccepted(exchange, urlPath, transfers.startImport(request, content), false);
        }
    }

    /**
     * The reviewed upload's answer (docs/csv-import.md decision 1): 200 with the report and the
     * confirm token, or 422 with the report and no token.
     *
     * <p>Deliberately not {@code respondAccepted}'s 202 and {@code Location}. Nothing was
     * accepted for processing — that is the entire point of a review — and a {@code Location}
     * pointing at the status resource would invite a caller to poll an import that cannot start
     * without a commit. The status code and the presence of the token are read off one fact, so
     * they cannot disagree: a token exists exactly when a committable set does.
     */
    private void respondReview(Exchange exchange, FileTransferService.ImportReview review)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rowCount", review.rows());
        body.put("ready", review.ready());
        body.put("rejected", review.rejected());
        if (review.fileError() != null) {
            body.put("fileError", review.fileError());
        }
        if (!review.errors().isEmpty()) {
            body.put("errors", errorRows(review.errors()));
        }
        if (review.committable()) {
            body.put("token", review.batchId());
            body.put("commitUrl", io.tesseraql.pipeline.BasePath.url(exchange,
                    urlPath + "/" + review.batchId() + "/commit"));
            body.put("expiresAt", String.valueOf(review.expiresAt()));
        }
        exchange.response().status(review.committable() ? 200 : 422);
        exchange.response().header(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.setBody(MAPPER.writeValueAsString(body));
    }

    /**
     * A rejection as the wire sees it. {@code field} and {@code value} ride only when the parse
     * knew them — a failing per-row statement does not — so a caller can tell a value the file
     * got wrong from a write the database refused. {@code detail} is the driver's own text on
     * that second kind: this is the transfer's operational face, not the author's page, and it
     * is the one place the diagnosis the report withholds stays readable.
     */
    static java.util.List<Map<String, Object>> errorRows(
            java.util.List<FileTransferService.RowError> errors) {
        return errors.stream().map(error -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("row", error.row());
            if (error.field() != null) {
                row.put("field", error.field());
            }
            if (error.value() != null) {
                row.put("value", error.value());
            }
            row.put("message", String.valueOf(error.message()));
            if (error.detail() != null) {
                row.put("detail", error.detail());
            }
            return row;
        }).toList();
    }

    /** The principal parking or spending a batch; a batch is one subject's to commit. */
    static String subject(Exchange exchange) {
        io.tesseraql.security.Principal principal = exchange.getProperty(
                TesseraqlProperties.PRINCIPAL, io.tesseraql.security.Principal.class);
        return principal == null ? "" : String.valueOf(principal.subject());
    }

    /**
     * The uploaded file content as a stream: for {@code multipart/form-data} the first file part
     * (a part named {@code file} preferred, streamed from Vert.x's on-disk upload), otherwise the
     * raw request body.
     */
    private static InputStream body(Exchange exchange) throws Exception {
        io.tesseraql.pipeline.Part part = io.tesseraql.pipeline.Uploads.filePart(exchange)
                .orElse(null);
        if (part != null) {
            return part.open();
        }
        Object body = exchange.getBody();
        if (body instanceof InputStream in) {
            return in;
        }
        if (body instanceof byte[] bytes) {
            return new java.io.ByteArrayInputStream(bytes);
        }
        if (body instanceof String text) {
            return new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        }
        byte[] converted = exchange.getBody(byte[].class);
        return converted == null ? null : new java.io.ByteArrayInputStream(converted);
    }

    /** The shared 202 response: transfer id plus the status (and for exports file) URLs. */
    static void respondAccepted(Exchange exchange, String urlPath, String transferId,
            boolean withFileUrl) {
        // The route's declared path is base-relative; what a caller is handed must be an address
        // this runtime answers at (docs/base-path.md).
        String statusUrl = io.tesseraql.pipeline.BasePath.url(exchange, urlPath + "/" + transferId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transferId", transferId);
        body.put("statusUrl", statusUrl);
        if (withFileUrl) {
            body.put("fileUrl", statusUrl + "/file");
        }
        exchange.response().status(202);
        // 202 points at the status resource (docs/vocabulary-cleanup.md slice 3); the body
        // keeps statusUrl/fileUrl for existing consumers.
        exchange.response().header("Location", statusUrl);
        exchange.response().header(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        try {
            exchange.setBody(MAPPER.writeValueAsString(body));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
}
