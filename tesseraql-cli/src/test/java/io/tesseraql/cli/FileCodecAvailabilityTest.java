package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.files.FileCodecs;
import org.junit.jupiter.api.Test;

/**
 * Guards that the CLI distribution bundles every first-party file-format codec, so
 * {@code tesseraql serve} can start any app — including the bundled example app, whose print route
 * exports {@code pdf}. The codecs register through the {@link FileCodecs} {@code ServiceLoader} SPI,
 * so this fails the moment a format module drops off the CLI classpath.
 */
class FileCodecAvailabilityTest {

    @Test
    void cliShipsTheCsvPdfAndExcelCodecs() {
        FileCodecs codecs = FileCodecs.discover();

        assertThat(codecs.require("csv")).isNotNull();
        assertThat(codecs.require("pdf")).isNotNull();
        assertThat(codecs.require("excel")).isNotNull();
    }
}
