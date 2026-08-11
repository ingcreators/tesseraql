package io.tesseraql.operations.catalog;

import io.tesseraql.core.catalog.CatalogStore;
import io.tesseraql.core.catalog.CodeCatalog;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.Durations;
import io.tesseraql.yaml.model.CatalogSpec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import javax.sql.DataSource;

/**
 * Loads code catalogs from the database and holds them (docs/lookups.md, decisions 8 and 14).
 *
 * <p>Four properties, each of which is a defect if left out:
 *
 * <ol>
 * <li><b>The swap is atomic.</b> A load builds a whole new catalog and replaces the reference.
 * Never clear-then-refill: a failure midway would empty every name on every screen.</li>
 * <li><b>A failed refresh keeps the previous data</b> and surfaces itself, rather than
 * degrading a page to blank codes because a partner database blinked.</li>
 * <li><b>One load at a time per catalog</b>, so a stale entry does not send every in-flight
 * request to the database at once.</li>
 * <li><b>A miss is not an answer.</b> {@link #reload} exists for the validation path, which
 * must re-read the source before rejecting a code that may simply be newer than the hold.</li>
 * </ol>
 */
public final class JdbcCatalogStore implements CatalogStore {

    /** TQL-APP-4206: a catalog refresh failed; the previous load is still serving. */
    private static final TqlErrorCode REFRESH_FAILED = new TqlErrorCode(TqlDomain.APP, 4206);

    private static final System.Logger LOG = System.getLogger(JdbcCatalogStore.class.getName());

    /**
     * One held catalog, when it was loaded, and the per-language views taken off it.
     *
     * <p>The views are memoized rather than rebuilt: a load's languages are fixed until the
     * next one, and a page resolving twenty coded columns should cost twenty map lookups.
     */
    private record Held(CodeCatalog catalog, long loadedAt, Map<String, CodeCatalog> views) {

        Held(CodeCatalog catalog, long loadedAt) {
            this(catalog, loadedAt, new ConcurrentHashMap<>());
        }

        CodeCatalog view(String tag, String defaultTag) {
            // A catalog with no language column narrows to itself, so the key is never null.
            return views.computeIfAbsent(tag == null ? "" : tag,
                    requested -> catalog.inLanguage(requested.isEmpty() ? null : requested,
                            defaultTag));
        }
    }

    private final Map<String, CatalogSpec> specs;
    private final Function<String, DataSource> datasources;
    private final String dialect;
    private final String defaultLanguage;
    private final LongSupplier clock;
    private final Map<String, Held> held = new ConcurrentHashMap<>();
    private final Map<String, Object> loadLocks = new ConcurrentHashMap<>();

    public JdbcCatalogStore(Map<String, CatalogSpec> specs,
            Function<String, DataSource> datasources, String dialect, String defaultLanguage) {
        this(specs, datasources, dialect, defaultLanguage, System::currentTimeMillis);
    }

    JdbcCatalogStore(Map<String, CatalogSpec> specs, Function<String, DataSource> datasources,
            String dialect, String defaultLanguage, LongSupplier clock) {
        this.specs = Map.copyOf(specs);
        this.datasources = datasources;
        this.dialect = dialect;
        this.defaultLanguage = defaultLanguage;
        this.clock = clock;
    }

    @Override
    public Map<String, CodeCatalog> catalogs(String tag) {
        Map<String, CodeCatalog> current = new LinkedHashMap<>();
        // The narrowing is memoized per language on the held load: a page showing twenty coded
        // columns must not rebuild twenty catalogs to render, and the languages a load carries
        // are fixed until the next one.
        specs.keySet().forEach(name -> current.put(name,
                held(name).view(tag, defaultLanguage)));
        return current;
    }

    @Override
    public CodeCatalog catalog(String name) {
        return held(name).catalog();
    }

    @Override
    public CodeCatalog reload(String name) {
        held.remove(name);
        // Unnarrowed on purpose: the validation path asks whether a code exists, which is a
        // question about the key set and not about any language.
        return held(name).catalog();
    }

    private Held held(String name) {
        Held current = held.get(name);
        if (current != null && !isStale(name, current)) {
            return current;
        }
        // One load per catalog: a stale entry must not send every in-flight request at once.
        synchronized (loadLocks.computeIfAbsent(name, ignored -> new Object())) {
            Held rechecked = held.get(name);
            if (rechecked != null && !isStale(name, rechecked)) {
                return rechecked;
            }
            try {
                Held loaded = new Held(load(name, specs.get(name)), clock.getAsLong());
                held.put(name, loaded);
                return loaded;
            } catch (SQLException | RuntimeException ex) {
                if (rechecked != null) {
                    // Serving yesterday's names beats serving none; the failure is loud, and a
                    // hold that keeps failing is what an operator needs to see, not a blank page.
                    // The previous load's language views ride along, so the retry costs nothing.
                    LOG.log(System.Logger.Level.WARNING, "Catalog ''{0}'' refresh failed;"
                            + " serving the previous load", name, ex);
                    Held renewed = new Held(rechecked.catalog(), clock.getAsLong(),
                            rechecked.views());
                    held.put(name, renewed);
                    return renewed;
                }
                throw new TqlException(REFRESH_FAILED, "Catalog '" + name
                        + "' could not be loaded and has never loaded: " + ex.getMessage(), ex);
            }
        }
    }

    private boolean isStale(String name, Held current) {
        Duration ttl = Durations.parse(specs.get(name).effectiveTtl());
        return clock.getAsLong() - current.loadedAt() >= ttl.toMillis();
    }

    private CodeCatalog load(String name, CatalogSpec spec) throws SQLException {
        DataSource dataSource = datasources.apply(spec.effectiveDatasource());
        if (dataSource == null) {
            throw new IllegalStateException("catalog '" + name + "' names datasource '"
                    + spec.effectiveDatasource() + "', which is not configured");
        }
        String sql = CatalogQuery.select(spec, dialect);
        List<Object> binds = List.copyOf(spec.where().values());
        List<CodeCatalog.Entry> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < binds.size(); i++) {
                statement.setObject(i + 1, binds.get(i));
            }
            boolean hasActive = spec.active() != null && !spec.active().isBlank();
            boolean hasLanguage = spec.language() != null && !spec.language().isBlank();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Object key = resultSet.getObject(1);
                    String label = resultSet.getString(2);
                    // No active column means every code is offered; a column decides per row.
                    boolean active = !hasActive || truthy(resultSet.getObject(3));
                    // No language column means the name answers in every language, which is
                    // what a single-language app has and must keep working as.
                    String language = hasLanguage
                            ? resultSet.getString(hasActive ? 4 : 3)
                            : null;
                    rows.add(new CodeCatalog.Entry(key, label, active, language));
                }
            }
        }
        reportMissingTranslations(name, rows);
        return CodeCatalog.of(name, rows);
    }

    /**
     * Reports which languages a load is short of, once per load (docs/lookups.md, decision 12).
     *
     * <p>Once per load, not once per request: a missing translation is a content gap someone
     * has to fill, and a per-request log turns it into noise that hides the next real failure.
     * A load is rare by construction, so this line is a report an operator can act on.
     */
    private static void reportMissingTranslations(String name, List<CodeCatalog.Entry> rows) {
        java.util.Set<String> languages = new java.util.LinkedHashSet<>();
        java.util.Set<Object> keys = new java.util.LinkedHashSet<>();
        java.util.Set<String> present = new java.util.LinkedHashSet<>();
        for (CodeCatalog.Entry row : rows) {
            if (row.language() == null) {
                return;
            }
            languages.add(row.language());
            keys.add(String.valueOf(row.key()));
            present.add(row.language() + " " + row.key());
        }
        Map<String, Integer> missing = new LinkedHashMap<>();
        languages.forEach(language -> {
            int absent = 0;
            for (Object key : keys) {
                if (!present.contains(language + " " + key)) {
                    absent++;
                }
            }
            if (absent > 0) {
                missing.put(language, absent);
            }
        });
        if (!missing.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, "Catalog ''{0}'' is missing translations: {1}"
                    + " (untranslated codes fall back to the default language)", name, missing);
        }
    }

    /** An active flag is a boolean, a number, or the text a legacy column stores it as. */
    private static boolean truthy(Object value) {
        return switch (value) {
            case null -> false;
            case Boolean bool -> bool;
            case Number number -> number.intValue() != 0;
            default -> {
                String text = String.valueOf(value).trim();
                yield "1".equals(text) || "Y".equalsIgnoreCase(text) || "T".equalsIgnoreCase(text)
                        || "true".equalsIgnoreCase(text);
            }
        };
    }
}
