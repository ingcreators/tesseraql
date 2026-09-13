package io.tesseraql.core.files;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.OrderedCopies;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * The file codecs available to this runtime, keyed by format (design ch. 28, 47): every
 * {@link FileCodec} on the classpath registers through {@link ServiceLoader}, so adding the
 * optional Excel module to the classpath is the whole install. An unknown format fails loudly.
 */
public final class FileCodecs {

    private static final TqlErrorCode UNKNOWN_FORMAT = new TqlErrorCode(TqlDomain.LD, 2801);

    private static final System.Logger LOG = System.getLogger(FileCodecs.class.getName());

    private final Map<String, FileCodec> codecs;

    private FileCodecs(Map<String, FileCodec> codecs) {
        this.codecs = OrderedCopies.map(codecs);
    }

    public static FileCodecs discover() {
        Map<String, FileCodec> codecs = new LinkedHashMap<>();
        ServiceLoader.load(FileCodec.class)
                .forEach(codec -> put(codecs, codec));
        return new FileCodecs(codecs);
    }

    /**
     * Registers a codec under its format, the last one put winning as it always has — and says
     * so when a different class takes a format another codec held (docs/export-hygiene.md P7):
     * a module codec answering {@code csv} silently decided every export and import that read
     * this set. A refusal here would fail every app carrying the module; the lint that names the
     * shape belongs to the codec-discovery work (F82 slice 2). The line describes THIS codec set:
     * the synchronous route discovers on its own loader and may never see the pair.
     */
    private static void put(Map<String, FileCodec> codecs, FileCodec codec) {
        FileCodec previous = codecs.put(codec.format(), codec);
        if (previous != null && previous.getClass() != codec.getClass()) {
            LOG.log(System.Logger.Level.WARNING, "File codec {0} replaces {1} for format ''{2}'' in"
                    + " this codec set - the last one registered wins; declare one codec per format",
                    codec.getClass().getName(), previous.getClass().getName(), codec.format());
        }
    }

    /**
     * Discovers codecs visible to {@code loader} — a hosted runtime passes its own module
     * loader so each application's codecs are its own declarations (docs/module-scope.md).
     */
    public static FileCodecs discover(ClassLoader loader) {
        Map<String, FileCodec> codecs = new LinkedHashMap<>();
        ServiceLoader.load(FileCodec.class, loader)
                .forEach(codec -> put(codecs, codec));
        return new FileCodecs(codecs);
    }

    public static FileCodecs of(FileCodec... codecs) {
        Map<String, FileCodec> byFormat = new LinkedHashMap<>();
        for (FileCodec codec : codecs) {
            put(byFormat, codec);
        }
        return new FileCodecs(byFormat);
    }

    /**
     * Whether a codec for {@code format} is present — what the lint asks before telling an author
     * that an export's module is neither declared nor available (docs/module-channel.md).
     */
    public boolean supports(String format) {
        return codecs.containsKey(format);
    }

    public FileCodec require(String format) {
        FileCodec codec = codecs.get(format);
        if (codec == null) {
            throw new TqlException(UNKNOWN_FORMAT, "No file codec for format '" + format
                    + "' - available: " + codecs.keySet()
                    + " (the excel format needs the tesseraql-excel module on the classpath)");
        }
        return codec;
    }
}
