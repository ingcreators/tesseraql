package io.tesseraql.core.files;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
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

    private final Map<String, FileCodec> codecs;

    private FileCodecs(Map<String, FileCodec> codecs) {
        this.codecs = Map.copyOf(codecs);
    }

    public static FileCodecs discover() {
        Map<String, FileCodec> codecs = new LinkedHashMap<>();
        ServiceLoader.load(FileCodec.class)
                .forEach(codec -> codecs.put(codec.format(), codec));
        return new FileCodecs(codecs);
    }

    /**
     * Discovers codecs visible to {@code loader} — a hosted runtime passes its own module
     * loader so each application's codecs are its own declarations (docs/module-scope.md).
     */
    public static FileCodecs discover(ClassLoader loader) {
        Map<String, FileCodec> codecs = new LinkedHashMap<>();
        ServiceLoader.load(FileCodec.class, loader)
                .forEach(codec -> codecs.put(codec.format(), codec));
        return new FileCodecs(codecs);
    }

    public static FileCodecs of(FileCodec... codecs) {
        Map<String, FileCodec> byFormat = new LinkedHashMap<>();
        for (FileCodec codec : codecs) {
            byFormat.put(codec.format(), codec);
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
