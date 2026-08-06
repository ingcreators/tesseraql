package io.tesseraql.yaml.secret;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads single-value secret files (password files, mounted secrets) tolerantly: a byte-order mark
 * selects the charset (UTF-8, UTF-16LE, UTF-16BE) and is stripped; without one the content must be
 * UTF-8. Windows PowerShell 5.1 writes {@code >} redirects as UTF-16LE with a BOM and
 * {@code -Encoding utf8} with a UTF-8 BOM, so the naive {@code Files.readString} either dies with
 * {@code MalformedInputException} or silently prepends U+FEFF to the secret — this helper accepts
 * both spellings and turns anything else into an error that says how to re-save the file.
 */
public final class SecretFiles {

    private SecretFiles() {
    }

    /**
     * Returns the trimmed text of {@code file}, honoring a UTF-8/UTF-16 byte-order mark and
     * defaulting to UTF-8 without one.
     *
     * @throws IOException if the file cannot be read or is not valid text in the detected charset
     */
    public static String readTrimmed(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        Charset charset = StandardCharsets.UTF_8;
        int offset = 0;
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB
                && bytes[2] == (byte) 0xBF) {
            offset = 3;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
            charset = StandardCharsets.UTF_16LE;
            offset = 2;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) {
            charset = StandardCharsets.UTF_16BE;
            offset = 2;
        }
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString().trim();
        } catch (CharacterCodingException ex) {
            throw new IOException(file + " is not valid " + charset
                    + " text. Save it as UTF-8 or ASCII - e.g. Windows PowerShell:"
                    + " Set-Content -Path " + file.getFileName() + " -Encoding ascii", ex);
        }
    }
}
