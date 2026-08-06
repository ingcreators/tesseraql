package io.tesseraql.yaml.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretFilesTest {

    @TempDir
    Path dir;

    private Path write(String name, byte[] bytes) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, bytes);
        return file;
    }

    @Test
    void readsPlainUtf8AndTrims() throws IOException {
        Path file = write("pw", "s3cr3t\n".getBytes(StandardCharsets.UTF_8));

        assertThat(SecretFiles.readTrimmed(file)).isEqualTo("s3cr3t");
    }

    @Test
    void stripsUtf8Bom() throws IOException {
        // PowerShell 5.1 `Set-Content -Encoding utf8` writes a UTF-8 BOM; it must not
        // become part of the secret (U+FEFF survives trim()).
        Path file = write("pw", ("\uFEFF" + "s3cr3t\r\n").getBytes(StandardCharsets.UTF_8));

        assertThat(SecretFiles.readTrimmed(file)).isEqualTo("s3cr3t");
    }

    @Test
    void readsUtf16LittleEndianWithBom() throws IOException {
        // PowerShell 5.1 `echo "..." > file` writes UTF-16LE with a BOM.
        Path file = write("pw", ("\uFEFF" + "s3cr3t\r\n").getBytes(StandardCharsets.UTF_16LE));

        assertThat(SecretFiles.readTrimmed(file)).isEqualTo("s3cr3t");
    }

    @Test
    void readsUtf16BigEndianWithBom() throws IOException {
        Path file = write("pw", ("\uFEFF" + "s3cr3t").getBytes(StandardCharsets.UTF_16BE));

        assertThat(SecretFiles.readTrimmed(file)).isEqualTo("s3cr3t");
    }

    @Test
    void readsNonAsciiUtf8Content() throws IOException {
        Path file = write("pw", "pässwörd-日本語\n".getBytes(StandardCharsets.UTF_8));

        assertThat(SecretFiles.readTrimmed(file)).isEqualTo("pässwörd-日本語");
    }

    @Test
    void bomlessNonUtf8FailsWithActionableMessage() {
        assertThatThrownBy(() -> SecretFiles.readTrimmed(
                write("pw", new byte[]{(byte) 0xE9, 0x61, 0x62})))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not valid UTF-8")
                .hasMessageContaining("Set-Content");
    }

    @Test
    void emptyFileReadsAsEmpty() throws IOException {
        assertThat(SecretFiles.readTrimmed(write("pw", new byte[0]))).isEmpty();
    }
}
