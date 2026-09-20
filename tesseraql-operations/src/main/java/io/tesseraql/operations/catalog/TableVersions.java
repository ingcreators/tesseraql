package io.tesseraql.operations.catalog;

import io.tesseraql.core.cache.TableStamps;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;
import javax.sql.DataSource;

/**
 * The per-table version row every runtime of an application shares (docs/lookups.md decision
 * 14, docs/caching.md decision 5): {@code tql_catalog_version} on the main connector, one row
 * per table a code catalog or a held source reads. A command's {@code invalidates:} raises the
 * rows on the node that served it; every other node re-reads the table at most once per
 * {@link #STAMP_INTERVAL_MILLIS} — one small query for every catalog and hold at once — and
 * drops what moved.
 *
 * <p>Moved here from {@code JdbcCatalogStore} when the held sources arrived, so the catalog
 * store and the result hold consult one reader and one writer. The table keeps the catalog's
 * name: the row set is the same kind of thing, the DDL exists in three dialects and is applied
 * idempotently by every runtime, and a rename would leave an orphan in every deployed
 * database for a word.
 *
 * <p>Two properties, each of which is a defect if omitted: a stamp that cannot be read makes
 * nothing unreadable — the holds expire on their TTL alone — and a bump that fails is logged,
 * never the command's failure. The stamp is an optimization; the TTL is the guarantee.
 */
public final class TableVersions implements TableStamps {

    private static final System.Logger LOG = System.getLogger(TableVersions.class.getName());

    /** How often a runtime re-reads the version table: often enough to feel immediate. */
    public static final long STAMP_INTERVAL_MILLIS = 5_000L;

    /** One row of the version table, for the operations surface. */
    public record Stamp(String table, long version, long updatedAt) {
    }

    private final Function<String, DataSource> datasources;
    private final Set<String> stampedTables;
    private final LongSupplier clock;
    /** The snapshot has not been read yet (or this node just raised rows and must re-read). */
    private static final long NEVER_READ = Long.MIN_VALUE;

    private final Map<String, Long> stamps = new ConcurrentHashMap<>();
    private final Map<String, Long> updatedAt = new ConcurrentHashMap<>();
    private final AtomicLong stampsReadAt = new AtomicLong(NEVER_READ);
    private volatile boolean stamped;

    /**
     * @param datasources   the connectors by name; the rows live on {@code main}
     * @param stampedTables every table a catalog or a held source of this application reads —
     *                      the only rows this runtime raises, so the set stays the declared one
     *                      rather than growing a row for every table any command names
     * @param clock         epoch millis; injectable for the tests
     */
    public TableVersions(Function<String, DataSource> datasources, Set<String> stampedTables,
            LongSupplier clock) {
        this.datasources = datasources;
        this.stampedTables = Set.copyOf(stampedTables);
        this.clock = clock;
    }

    /**
     * Creates {@code tql_catalog_version} on the main connector if it is not there.
     *
     * <p>A failure disables stamping rather than the catalogs or the holds: an app whose
     * database user cannot create the table still resolves every name and serves every hold,
     * and they expire on the TTL — the guarantee the stamp was only ever an optimization over.
     */
    public void ensureSchema() {
        DataSource main = datasources.apply("main");
        if (main == null) {
            return;
        }
        try {
            io.tesseraql.core.util.SqlScripts.applyForVendor(main, TableVersions.class,
                    "/tesseraql/db/migration/catalog/V1__catalog_version.sql");
            stamped = true;
        } catch (SQLException | RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Could not create tql_catalog_version;"
                    + " catalog and result holds will expire on TTL only", ex);
        }
    }

    /** Whether the version table exists and is read; false until {@link #ensureSchema} succeeds. */
    public boolean stamped() {
        return stamped;
    }

    /** The tables this runtime raises rows for. */
    public Set<String> stampedTables() {
        return stampedTables;
    }

    /**
     * The highest version among {@code tables}, from a snapshot re-read at most once per
     * {@link #STAMP_INTERVAL_MILLIS}. The interval is what keeps a per-request staleness check
     * from becoming a per-request query — the point of a hold is that serving costs none.
     */
    @Override
    public long versionOf(Collection<String> tables) {
        if (!stamped || tables == null || tables.isEmpty()) {
            return 0L;
        }
        refresh();
        long highest = 0L;
        for (String table : tables) {
            highest = Math.max(highest, stamps.getOrDefault(table, 0L));
        }
        return highest;
    }

    /**
     * Raises the version of each written table so the other runtimes reload (decision 14).
     *
     * <p>After the commit, like the local drop, and deliberately not inside the command's
     * transaction: the stamp is an <em>optimization</em> — the hold's expiry and the validation
     * path's re-read are the guarantee — so putting a write into every maintenance transaction
     * to make a cache hint atomic would buy nothing the TTL does not already bound. A crash
     * between the commit and the bump leaves the other runtimes on the old rows until the hold
     * expires, which is the same bounded display delay a master written by another system
     * gives.
     *
     * <p>A failure here is logged and swallowed for the same reason: an operator's save must not
     * fail because a cache hint could not be written.
     */
    @Override
    public void bump(Collection<String> tables) {
        if (tables == null || tables.isEmpty()) {
            return;
        }
        DataSource main = datasources.apply("main");
        if (main == null || !stamped) {
            return;
        }
        Set<String> written = new LinkedHashSet<>(tables);
        try (Connection connection = main.getConnection()) {
            for (String table : written) {
                if (!stampedTables.contains(table)) {
                    // Only tables a catalog or a held source reads: the row set stays the
                    // declared ones rather than growing a row for every table any command names.
                    continue;
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "update tql_catalog_version set version = version + 1,"
                                + " updated_at = ? where table_name = ?")) {
                    update.setTimestamp(1, new java.sql.Timestamp(clock.getAsLong()));
                    update.setString(2, table);
                    if (update.executeUpdate() == 0) {
                        insert(connection, table);
                    }
                }
            }
            // This node raised the rows; read them back at once so its own next staleness check
            // does not serve the interval-old snapshot as "current" over a hold it just dropped.
            stampsReadAt.set(NEVER_READ);
        } catch (SQLException ex) {
            LOG.log(System.Logger.Level.WARNING, "Could not raise the table version for {0};"
                    + " other runtimes will reload when their hold expires", written, ex);
        }
    }

    private void insert(Connection connection, String table) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into tql_catalog_version (table_name, version, updated_at)"
                        + " values (?, 1, ?)")) {
            insert.setString(1, table);
            insert.setTimestamp(2, new java.sql.Timestamp(clock.getAsLong()));
            insert.executeUpdate();
        } catch (SQLException ex) {
            // Another runtime inserted the same row first, which is the outcome either way.
            LOG.log(System.Logger.Level.DEBUG, "Version row for {0} already exists", table);
        }
    }

    /** Whether the snapshot read at {@code readAt} still stands at {@code now}. */
    private static boolean recent(long readAt, long now) {
        // NEVER_READ is tested by identity, not by subtraction: `now - Long.MIN_VALUE`
        // overflows to a negative number, which read as "read a moment ago" — the catalog
        // store's reader carried that arithmetic and so never read the table at all, and a
        // peer node's bump reached nothing until the hold's own TTL (docs/caching.md S1).
        return readAt != NEVER_READ && now - readAt < STAMP_INTERVAL_MILLIS;
    }

    private void refresh() {
        long now = clock.getAsLong();
        if (recent(stampsReadAt.get(), now)) {
            return;
        }
        synchronized (stamps) {
            if (recent(stampsReadAt.get(), clock.getAsLong())) {
                return;
            }
            DataSource main = datasources.apply("main");
            if (main == null) {
                return;
            }
            try (Connection connection = main.getConnection();
                    PreparedStatement select = connection.prepareStatement(
                            "select table_name, version, updated_at from tql_catalog_version");
                    ResultSet rows = select.executeQuery()) {
                Map<String, Long> read = new java.util.HashMap<>();
                Map<String, Long> readAt = new java.util.HashMap<>();
                while (rows.next()) {
                    read.put(rows.getString(1), rows.getLong(2));
                    java.sql.Timestamp at = rows.getTimestamp(3);
                    readAt.put(rows.getString(1), at == null ? 0L : at.getTime());
                }
                stamps.clear();
                stamps.putAll(read);
                updatedAt.clear();
                updatedAt.putAll(readAt);
                stampsReadAt.set(now);
            } catch (SQLException ex) {
                // Falling back to the TTL alone: a stamp that cannot be read must not make a
                // catalog or a hold unreadable, and the hold still expires.
                LOG.log(System.Logger.Level.WARNING,
                        "Could not read the table version table; holds expire on TTL only", ex);
                stampsReadAt.set(now);
            }
        }
    }

    /** Every version row as last read, in table order, for the operations surface. */
    public List<Stamp> status() {
        if (stamped) {
            refresh();
        }
        List<Stamp> out = new ArrayList<>();
        stamps.keySet().stream().sorted().forEach(table -> out.add(
                new Stamp(table, stamps.get(table), updatedAt.getOrDefault(table, 0L))));
        return out;
    }
}
