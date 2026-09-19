package io.tesseraql.core.files;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.OrderedCopies;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * The file codecs of one application, keyed by format (design ch. 28, 47): every
 * {@link FileCodec} a class loader can see registers through {@link ServiceLoader}, so
 * declaring the optional Excel module is the whole install. An unknown format fails loudly.
 *
 * <p>Every discovery names its loader (docs/codec-discovery.md decision 1). A runtime
 * discovers once on its application's module loader and hands the one instance to the route
 * compiler, the reloader and the transfer service; a CLI verb that composed the thread context
 * loader passes that loader, spelled out. The overload that read the context loader on its own
 * is gone: it is how the synchronous export route came to see a different codec set from the
 * asynchronous one under {@code tesseraql dev}.
 */
public final class FileCodecs {

    private static final TqlErrorCode UNKNOWN_FORMAT = new TqlErrorCode(TqlDomain.LD, 2801);

    private static final System.Logger LOG = System.getLogger(FileCodecs.class.getName());

    private final Map<String, FileCodec> codecs;

    private FileCodecs(Map<String, FileCodec> codecs) {
        this.codecs = OrderedCopies.map(codecs);
    }

    /**
     * Registers a codec under its format, the last one put winning as it always has — and says
     * so when a different class takes a format another codec held (docs/export-hygiene.md P7):
     * a module codec answering {@code csv} silently decided every export and import that read
     * this set. A refusal here would fail every app carrying the module, so the line is the
     * whole answer today — no lint names two codecs on one format (docs/codec-discovery.md
     * decision 3 lints an unknown format; a duplicate-format lint was scoped and
     * never shipped). The line describes THIS codec set, which since decision 1 is the one set
     * every arm of an application reads.
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
     * Discovers the codecs visible to {@code loader} — a runtime passes its application's module
     * loader, so each application's codecs are its own declarations (docs/module-scope.md).
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
        return require(format, "");
    }

    /**
     * The codec for {@code format}, or the refusal prefixed with the declaration's site — the
     * app, the route or the job step and the key (docs/codec-discovery.md decision 2) — so a
     * boot that refuses names what to fix. The formats that live in modules are named as such
     * whatever was asked for: the old text hinted at excel for every absent format.
     */
    public FileCodec require(String format, String at) {
        FileCodec codec = codecs.get(format);
        if (codec == null) {
            throw new TqlException(UNKNOWN_FORMAT, at + "no file codec for format '" + format
                    + "' - available: " + codecs.keySet() + "; pdf and excel are modules,"
                    + " declared under tesseraql.modules (docs/printable-documents.md,"
                    + " docs/file-transfers.md), and an application's own codec arrives the"
                    + " same way");
        }
        return codec;
    }
}
