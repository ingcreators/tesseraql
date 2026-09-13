package io.tesseraql.core.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * A codec that replaces another's format is said so (docs/export-hygiene.md P7, item 3): the
 * set keeps the last one put — a module codec answering {@code csv} decides every export and
 * import that reads the set — and nothing in any sink used to say that it had. One WARNING at
 * the {@code put} site, naming the format and both classes; the lint and any refusal are F82
 * slice 2's.
 *
 * <p>The line goes through {@code System.Logger}, whose default backend is JUL and whose console
 * handler bound stderr at JVM start — a captured {@code System.err} sees nothing, so the guard
 * attaches a JUL handler and proves the capture with a control line first.
 */
class FileCodecsTest {

    /** The records the {@code FileCodecs} logger emitted while {@code body} ran. */
    private static List<LogRecord> logged(Runnable body) {
        Logger logger = Logger.getLogger(FileCodecs.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Level level = logger.getLevel();
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        try {
            body.run();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(level);
        }
        return records;
    }

    @Test
    void theHandlerSeesALine() {
        // The sensitivity control: a line logged through the same logger reaches the handler.
        List<LogRecord> records = logged(() -> Logger.getLogger(FileCodecs.class.getName())
                .warning("control"));
        assertThat(records).singleElement().extracting(LogRecord::getMessage).isEqualTo("control");
    }

    @Test
    void aCodecReplacingAnothersFormatIsWarnedAboutOnce() {
        List<LogRecord> records = logged(() -> FileCodecs.of(new First("csv"), new Second("csv")));
        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.WARNING);
            // The formatted line, not the pattern: a record carries {0} and its parameters apart.
            String line = new java.util.logging.SimpleFormatter().formatMessage(record);
            assertThat(line).contains("'csv'").contains("FileCodecsTest$First")
                    .contains("FileCodecsTest$Second").contains("replaces");
        });
        // Last put wins, as before: the replacement is the codec the set answers with.
        assertThat(FileCodecs.of(new First("csv"), new Second("csv")).require("csv"))
                .isInstanceOf(Second.class);
    }

    @Test
    void distinctFormatsAndTheSameClassTwiceAreQuiet() {
        assertThat(logged(() -> FileCodecs.of(new First("csv"), new Second("excel")))).isEmpty();
        // The same class on two loaders instantiates twice with one name: a re-registration of
        // the same codec class is not a replacement.
        assertThat(logged(() -> FileCodecs.of(new First("csv"), new First("csv")))).isEmpty();
    }

    private static class First implements FileCodec {
        private final String format;

        First(String format) {
            this.format = format;
        }

        @Override
        public String format() {
            return format;
        }

        @Override
        public String contentType() {
            return "text/plain";
        }

        @Override
        public String extension() {
            return ".txt";
        }

        @Override
        public void read(InputStream in, FileReadSpec spec, RowHandler handler) {
        }

        @Override
        public void write(OutputStream out, FileWriteSpec spec, ExportModel model) {
        }
    }

    private static final class Second extends First {
        Second(String format) {
            super(format);
        }
    }
}
