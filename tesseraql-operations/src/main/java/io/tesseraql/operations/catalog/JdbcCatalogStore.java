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
    private record Held(CodeCatalog catalog, long loadedAt, Map<String, CodeCatalog> views,
            String lastError) {

        Held(CodeCatalog catalog, long loadedAt) {
            this(catalog, loadedAt, new ConcurrentHashMap<>(), null);
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
    private final java.nio.file.Path appHome;
    private final io.tesseraql.yaml.i18n.I18nSettings i18n;
    private final LongSupplier clock;
    private final Map<String, Held> held = new ConcurrentHashMap<>();
    private final Map<String, Object> loadLocks = new ConcurrentHashMap<>();

    public JdbcCatalogStore(Map<String, CatalogSpec> specs,
            Function<String, DataSource> datasources, String dialect,
            java.nio.file.Path appHome, io.tesseraql.yaml.i18n.I18nSettings i18n) {
        this(specs, datasources, dialect, appHome, i18n, System::currentTimeMillis);
    }

    JdbcCatalogStore(Map<String, CatalogSpec> specs, Function<String, DataSource> datasources,
            String dialect, java.nio.file.Path appHome,
            io.tesseraql.yaml.i18n.I18nSettings i18n, LongSupplier clock) {
        this.specs = Map.copyOf(specs);
        this.datasources = datasources;
        this.dialect = dialect;
        this.appHome = appHome;
        this.i18n = i18n;
        this.clock = clock;
    }

    @Override
    public Map<String, CodeCatalog> catalogs(String tag) {
        Map<String, CodeCatalog> current = new LinkedHashMap<>();
        // The narrowing is memoized per language on the held load: a page showing twenty coded
        // columns must not rebuild twenty catalogs to render, and the languages a load carries
        // are fixed until the next one.
        specs.keySet().forEach(name -> current.put(name,
                held(name).view(tag, i18n.defaultTag())));
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

    @Override
    public void invalidate(java.util.Collection<String> tables) {
        if (tables == null || tables.isEmpty()) {
            return;
        }
        java.util.Set<String> changed = new java.util.LinkedHashSet<>(tables);
        specs.forEach((name, spec) -> {
            // sourceTables() is the single table: or the file: form's declared tables:, so a
            // joined catalog is reachable from a maintenance command exactly like a plain one.
            if (spec.sourceTables().stream().anyMatch(changed::contains)) {
                // Dropping the hold, not reloading it here: the write path must not pay for a
                // load, and the next reader takes it under the same one-load-at-a-time lock.
                held.remove(name);
            }
        });
    }

    @Override
    public java.util.List<CatalogStore.Status> status() {
        java.util.List<CatalogStore.Status> statuses = new ArrayList<>();
        specs.forEach((name, spec) -> {
            Held current = held.get(name);
            statuses.add(new CatalogStore.Status(name, spec.sourceTables(),
                    current == null ? -1 : current.catalog().size(),
                    current == null ? List.of() : languages(current.catalog()),
                    current == null ? null : current.loadedAt(),
                    current == null ? null : current.lastError()));
        });
        return statuses;
    }

    /** The languages a load carried, in first-seen order; empty for a single-language catalog. */
    private static List<String> languages(CodeCatalog catalog) {
        java.util.Set<String> tags = new java.util.LinkedHashSet<>();
        catalog.all().forEach(entry -> {
            if (entry.language() != null) {
                tags.add(entry.language());
            }
        });
        return List.copyOf(tags);
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
                    // The error rides the hold: a catalog serving yesterday's names while its
                    // refresh keeps failing must not read as healthy on the ops surface.
                    Held renewed = new Held(rechecked.catalog(), clock.getAsLong(),
                            rechecked.views(), String.valueOf(ex.getMessage()));
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
        String sql = spec.file() != null ? fileSql(name, spec) : CatalogQuery.select(spec, dialect);
        // A file: catalog takes no binds — it has no where: to bind, because its SQL owns its
        // own filtering. The table: form binds its where: values, never interpolates them.
        List<Object> binds = spec.file() != null ? List.of() : List.copyOf(spec.where().values());
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
                    // By name, not by position: a file: catalog's SELECT is the author's, and
                    // the declared key:/label:/active:/language: name its result columns just
                    // as they name a table's.
                    Object key = resultSet.getObject(spec.key());
                    // No active column means every code is offered; a column decides per row.
                    boolean active = !hasActive || truthy(resultSet.getObject(spec.active()));
                    // No language column means the name answers in every language, which is
                    // what a single-language app has and must keep working as.
                    String language = hasLanguage ? resultSet.getString(spec.language()) : null;
                    if (spec.label().column() != null) {
                        rows.add(new CodeCatalog.Entry(key,
                                resultSet.getString(spec.label().column()), active, language));
                    } else {
                        messageRows(spec, key, active, rows);
                    }
                }
            }
        }
        reportMissingTranslations(name, rows);
        return CodeCatalog.of(name, rows);
    }

    /**
     * One code's names taken from the message catalog (docs/lookups.md, decision 12).
     *
     * <p>The load says which codes exist; the names are already in the translation workflow the
     * Studio message editor serves, so a code becomes one row per locale the app supports and
     * the language dimension falls out of the message catalog instead of a table beside it. A
     * locale with no message for a code contributes no row, which is exactly what makes the
     * narrowing fall back to the default language rather than to a key string.
     */
    private void messageRows(CatalogSpec spec, Object key, boolean active,
            List<CodeCatalog.Entry> rows) {
        String messageKey = spec.label().messageFor(key);
        for (String tag : i18n.supportedTags()) {
            String label = i18n.catalog().resolve(tag, messageKey);
            if (label != null) {
                rows.add(new CodeCatalog.Entry(key, label, active, tag));
            }
        }
    }

    /**
     * The SQL behind a {@code file:} catalog, read from the app's {@code catalogs/} directory.
     *
     * <p>Resolved under {@code catalogs/} and refused if it escapes — the file name comes from a
     * document, and a catalog is not a way to read an arbitrary path off the host.
     */
    private String fileSql(String name, CatalogSpec spec) {
        java.nio.file.Path home = appHome.toAbsolutePath().normalize();
        java.nio.file.Path file = home.resolve("catalogs").resolve(spec.file()).normalize();
        if (!file.startsWith(home.resolve("catalogs"))
                || !java.nio.file.Files.isRegularFile(file)) {
            throw new TqlException(CatalogSpec.INVALID_SOURCE, "Catalog '" + name + "': file '"
                    + spec.file() + "' does not resolve to a SQL file under catalogs/");
        }
        try {
            return java.nio.file.Files.readString(file);
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
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
