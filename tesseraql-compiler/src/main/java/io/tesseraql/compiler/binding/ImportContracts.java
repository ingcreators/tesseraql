package io.tesseraql.compiler.binding;

import io.tesseraql.core.catalog.CatalogStore;
import io.tesseraql.core.catalog.CodeCatalog;
import io.tesseraql.core.files.RowContract;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.yaml.model.InputField;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Freezes a file-import route's {@code input:} block into the contract each row is held to
 * (docs/csv-import.md decision 3).
 *
 * <p>On an import route the body is rows, so {@code input:} describes a <em>row</em> rather than
 * the request — which is what lets a file reuse the vocabulary a form already declares, domains
 * and code catalogs included, instead of growing a second one on the column list. The column
 * list keeps the job it had: which cell feeds which bind name.
 *
 * <p>Two things happen here rather than in the row loop, and both are the point. Each referenced
 * catalog is read <b>once</b>: the binder's per-value path reloads a catalog from its source
 * table whenever a value misses, which costs one query per rejection on a form and one per
 * rejected row in a file. And the answer is <b>frozen</b> into the contract, so the commit pass
 * of a reviewed import compares against the same code set the review did — a code retired during
 * the review window cannot move the rejection set and make the agreement check blame the file.
 */
public final class ImportContracts {

    private ImportContracts() {
    }

    /**
     * The contract for these declared inputs, with every {@code codes:} reference resolved
     * against the store this exchange can see. An app with no catalogs has no store bound, and a
     * column referencing one then constrains nothing rather than refusing everything.
     */
    public static RowContract of(Exchange exchange, Map<String, InputField> input) {
        if (input == null || input.isEmpty()) {
            return RowContract.none();
        }
        CatalogStore catalogs = exchange.beans().lookup(TesseraqlProperties.CATALOG_STORE_BEAN,
                CatalogStore.class);
        Map<String, Set<String>> resolved = new LinkedHashMap<>();
        RowContract.Builder contract = new RowContract.Builder();
        input.forEach((name, field) -> contract.column(name, field.required(), field.min(),
                field.max(), field.minLength(), field.maxLength(), field.pattern(),
                field.hasStringFormat() ? field.format() : null, field.enumValues(),
                codes(catalogs, resolved, field.codes())));
        return contract.build();
    }

    /**
     * The active codes of one catalog, read at most once per import however many columns name
     * it. Null — meaning "this column is not code-constrained" — when the app binds no store or
     * the catalog does not resolve: an import must not refuse every row because a catalog is
     * missing, which is a deployment fault and not the file's.
     */
    private static Set<String> codes(CatalogStore catalogs, Map<String, Set<String>> resolved,
            String catalogName) {
        if (catalogName == null || catalogName.isBlank() || catalogs == null) {
            return null;
        }
        if (resolved.containsKey(catalogName)) {
            return resolved.get(catalogName);
        }
        // One fresh read per catalog per import, not the held copy. The binder's per-value path
        // reloads on a miss precisely so a code added a minute ago is not refused for the length
        // of the hold; a file cannot afford that per value, so it pays for it once here instead
        // of inheriting a snapshot that may be an hour old.
        CodeCatalog catalog = catalogs.reload(catalogName);
        Set<String> keys = null;
        if (catalog != null) {
            // options(), never has(): a retired code must still render on last year's rows and
            // must not be accepted on today's import — the same line the binder draws. Entries
            // repeat per language on a translated catalog, so the set does the deduping.
            keys = new LinkedHashSet<>();
            for (CodeCatalog.Entry entry : catalog.options()) {
                keys.add(String.valueOf(entry.key()));
            }
        }
        resolved.put(catalogName, keys);
        return keys;
    }
}
