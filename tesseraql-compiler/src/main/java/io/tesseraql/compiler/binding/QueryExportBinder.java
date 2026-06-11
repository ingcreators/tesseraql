package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.files.FileCodec;
import io.tesseraql.core.files.FileWriteSpec;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Binds the synchronous {@code query-export} route to the file-transfer encoding machinery
 * (design ch. 28.10): the route's codec and write spec - with the per-request locale and time
 * zone resolved like {@code file-export} does - travel to the SQL component as exchange
 * properties, so both recipes share column mapping, formats, and codecs.
 */
public final class QueryExportBinder implements Processor {

    private final FileCodec codec;
    private final FileWriteSpec writeSpec;
    private final String localeDeclaration;
    private final String timezoneDeclaration;

    public QueryExportBinder(FileCodec codec, FileWriteSpec writeSpec,
            String localeDeclaration, String timezoneDeclaration) {
        this.codec = codec;
        this.writeSpec = writeSpec;
        this.localeDeclaration = localeDeclaration;
        this.timezoneDeclaration = timezoneDeclaration;
    }

    @Override
    public void process(Exchange exchange) {
        exchange.setProperty(TesseraqlProperties.EXPORT_CODEC, codec);
        exchange.setProperty(TesseraqlProperties.EXPORT_SPEC, writeSpec.withFormatting(
                FormatSources.resolve(exchange, localeDeclaration),
                FormatSources.resolve(exchange, timezoneDeclaration)));
    }
}
