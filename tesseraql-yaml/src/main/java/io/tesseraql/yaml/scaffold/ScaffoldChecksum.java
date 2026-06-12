package io.tesseraql.yaml.scaffold;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The scaffold's edit-detection mechanism (roadmap Phase 23, design ch. 22.20): every generated
 * file carries one checksum comment line over the rest of its own content, so regeneration can
 * tell a pristine generated file (safe to overwrite) from one the user edited (left alone) — with
 * no ledger outside the file itself.
 *
 * <p>The checksum line is a comment in the file's own syntax ({@code #} for YAML, {@code --} for
 * SQL, {@code <!-- -->} for HTML) and is excluded from its own hash. Deleting the line hands the
 * file over to the user permanently: without a marker it is treated as foreign and never
 * overwritten.
 */
public final class ScaffoldChecksum {

    /** How an on-disk file relates to the scaffold that would regenerate it. */
    public enum Status {
        /** The file does not exist yet. */
        MISSING,
        /** The file carries a checksum matching its content: pristine generated output. */
        PRISTINE,
        /** The file carries a checksum that no longer matches: the user edited it. */
        EDITED,
        /** The file carries no scaffold marker: user-owned, never touched. */
        FOREIGN
    }

    private static final Pattern MARKER = Pattern.compile(
            "^(?:#|--) tesseraql-scaffold-checksum: sha256:([0-9a-f]{64})$"
                    + "|^<!-- tesseraql-scaffold-checksum: sha256:([0-9a-f]{64}) -->$",
            Pattern.MULTILINE);
    private static final String DOCTYPE = "<!DOCTYPE html>\n";

    private ScaffoldChecksum() {
    }

    /**
     * Stamps generated content with its checksum line. The line becomes the first line of the
     * file, except in HTML documents where it follows the doctype so browsers never see content
     * before {@code <!DOCTYPE html>}.
     */
    public static String stamp(String path, String content) {
        String marker = markerLine(path, sha256(withoutMarker(content)));
        if (path.endsWith(".html") && content.startsWith(DOCTYPE)) {
            return DOCTYPE + marker + "\n" + content.substring(DOCTYPE.length());
        }
        return marker + "\n" + content;
    }

    /** Classifies on-disk content against the marker contract. */
    public static Status status(String content) {
        Matcher matcher = MARKER.matcher(content);
        if (!matcher.find()) {
            return Status.FOREIGN;
        }
        String recorded = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        return recorded.equals(sha256(withoutMarker(content))) ? Status.PRISTINE : Status.EDITED;
    }

    private static String markerLine(String path, String checksum) {
        String name = path.toLowerCase(Locale.ROOT);
        if (name.endsWith(".sql")) {
            return "-- tesseraql-scaffold-checksum: sha256:" + checksum;
        }
        if (name.endsWith(".html")) {
            return "<!-- tesseraql-scaffold-checksum: sha256:" + checksum + " -->";
        }
        return "# tesseraql-scaffold-checksum: sha256:" + checksum;
    }

    /** The content with the marker line (and its newline) removed — the hashed bytes. */
    private static String withoutMarker(String content) {
        Matcher matcher = MARKER.matcher(content);
        if (!matcher.find()) {
            return content;
        }
        int end = matcher.end();
        if (end < content.length() && content.charAt(end) == '\n') {
            end++;
        }
        return content.substring(0, matcher.start()) + content.substring(end);
    }

    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
