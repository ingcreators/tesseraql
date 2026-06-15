package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Locale;

/**
 * A {@code kind: attachment} document under {@code attachments/} (roadmap Phase 30 — attachments and
 * object storage): binds uploaded files to an owning business record and synthesizes an off-heap
 * upload route, a list route, and a download route.
 *
 * <p>The metadata lives in a managed {@code tql_attachment} table; the blob bytes live in a
 * {@link io.tesseraql.core.blob.BlobStore} (the local file store by default, S3 in the opt-in
 * {@code tesseraql-s3} module). Download authorization rides the route's {@code security:} plus the
 * owning-record key in {@code basePath}.
 *
 * @param version  the DSL version, e.g. {@code tesseraql/v1}
 * @param id       the attachment document id (drives the synthesized route ids)
 * @param kind     always {@code attachment}
 * @param basePath the HTTP base path; must contain the owning record's {@code key} as a path param
 * @param record   the owning business record an attachment is bound to
 * @param bucket   the logical bucket namespace the blobs are stored under
 * @param limits   the upload constraints (max size, allowed content types)
 * @param security the auth/policy applied to the synthesized routes
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AttachmentDefinition(String version, String id, String kind, String basePath,
        RecordSpec record, String bucket, Limits limits, SecuritySpec security) {

    /** The owning business record an attachment is bound to. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecordSpec(String entity, String key) {
    }

    /** Upload constraints. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Limits(String maxBytes, List<String> contentTypes) {

        public Limits {
            contentTypes = contentTypes == null ? List.of() : List.copyOf(contentTypes);
        }

        /** The {@code maxBytes} as a byte count, or {@code -1} when absent or unparseable. */
        public long maxBytesValue() {
            return parseSize(maxBytes);
        }

        /** Whether {@code contentType} is allowed (empty list allows everything). */
        public boolean allows(String contentType) {
            if (contentTypes.isEmpty()) {
                return true;
            }
            String candidate = contentType == null ? "" : baseType(contentType);
            return contentTypes.stream().anyMatch(t -> baseType(t).equalsIgnoreCase(candidate));
        }

        private static String baseType(String contentType) {
            int semicolon = contentType.indexOf(';');
            return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim();
        }

        /** Parses a size like {@code 25MB} / {@code 1048576} into bytes; {@code -1} when invalid. */
        public static long parseSize(String text) {
            if (text == null || text.isBlank()) {
                return -1;
            }
            String value = text.trim().toUpperCase(Locale.ROOT);
            long multiplier = 1;
            if (value.endsWith("KB")) {
                multiplier = 1024L;
                value = value.substring(0, value.length() - 2);
            } else if (value.endsWith("MB")) {
                multiplier = 1024L * 1024L;
                value = value.substring(0, value.length() - 2);
            } else if (value.endsWith("GB")) {
                multiplier = 1024L * 1024L * 1024L;
                value = value.substring(0, value.length() - 2);
            } else if (value.endsWith("B")) {
                value = value.substring(0, value.length() - 1);
            }
            try {
                long number = Long.parseLong(value.trim());
                return number < 0 ? -1 : number * multiplier;
            } catch (NumberFormatException ex) {
                return -1;
            }
        }
    }
}
