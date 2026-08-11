package io.tesseraql.yaml.catalog;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.SimpleYamlParser;
import io.tesseraql.yaml.model.CatalogSpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The app's code catalogs (docs/lookups.md): small, nearly static tables of codes and names,
 * declared once under {@code catalogs/} and resolved from memory wherever a code is rendered.
 *
 * <pre>{@code
 * # catalogs/codes.yml
 * version: tesseraql/v1
 * catalogs:
 *   取引区分:
 *     table: 区分マスタ
 *     where: { 区分種別: '01' }
 *     key:   区分コード
 *     label: 区分名称
 *     order: 表示順
 *     active: 有効フラグ
 * }</pre>
 *
 * <p>Files merge into one app-wide namespace and a name declared twice is a build error, the
 * same posture {@code domains/} and {@code scope/} take.
 */
public final class Catalogs {

    /** TQL-FIELD-4617: a catalog name is declared twice across the catalogs/ documents. */
    private static final TqlErrorCode DUPLICATE = new TqlErrorCode(TqlDomain.FIELD, 4617);

    private final Map<String, CatalogSpec> catalogs;

    private Catalogs(Map<String, CatalogSpec> catalogs) {
        this.catalogs = java.util.Collections.unmodifiableMap(catalogs);
    }

    /** The empty set — an app with no {@code catalogs/} directory. */
    public static Catalogs empty() {
        return new Catalogs(new LinkedHashMap<>());
    }

    /** Loads every {@code catalogs/*.yml} under {@code appHome}, in file-name order. */
    public static Catalogs load(Path appHome) {
        Path dir = appHome.resolve("catalogs");
        Map<String, CatalogSpec> catalogs = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) {
            return new Catalogs(catalogs);
        }
        SimpleYamlParser parser = new SimpleYamlParser();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(file -> file.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .forEach(file -> parser.parseCatalogs(file).forEach((name, spec) -> {
                        if (catalogs.putIfAbsent(name, spec) != null) {
                            throw new TqlException(DUPLICATE, "Catalog '" + name
                                    + "' is declared twice (second: " + file + ")");
                        }
                    }));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return new Catalogs(catalogs);
    }

    /** The declared catalogs by name, in declaration order. */
    public Map<String, CatalogSpec> all() {
        return catalogs;
    }

    public boolean isEmpty() {
        return catalogs.isEmpty();
    }
}
