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
    private final io.tesseraql.core.files.ExportRowCap rowCap;
    private final java.util.List<io.tesseraql.core.files.ExportQuery> queries;
    private final java.util.Set<String> httpSources;
    private final java.util.List<EnrichProcessor> enrichments;

    public QueryExportBinder(FileCodec codec, FileWriteSpec writeSpec,
            String localeDeclaration, String timezoneDeclaration,
            io.tesseraql.core.files.ExportRowCap rowCap,
            java.util.List<io.tesseraql.core.files.ExportQuery> queries,
            java.util.Set<String> httpSources, java.util.List<EnrichProcessor> enrichments) {
        this.codec = codec;
        this.writeSpec = writeSpec;
        this.localeDeclaration = localeDeclaration;
        this.timezoneDeclaration = timezoneDeclaration;
        this.rowCap = rowCap;
        this.queries = java.util.List.copyOf(queries);
        this.httpSources = java.util.Set.copyOf(httpSources);
        this.enrichments = java.util.List.copyOf(enrichments);
    }

    @Override
    public void process(Exchange exchange) {
        exchange.setProperty(TesseraqlProperties.EXPORT_CODEC, codec);
        exchange.setProperty(TesseraqlProperties.EXPORT_SPEC, writeSpec.withFormatting(
                FormatSources.resolve(exchange, localeDeclaration),
                FormatSources.resolve(exchange, timezoneDeclaration)));
        exchange.setProperty(TesseraqlProperties.EXPORT_ROW_CAP, rowCap);
        exchange.setProperty(TesseraqlProperties.EXPORT_QUERIES, queries);
        exchange.setProperty(TesseraqlProperties.EXPORT_VALUES,
                ExportSources.values(exchange, httpSources));
        ExportEnrichment.bind(exchange, enrichments);
    }
}
