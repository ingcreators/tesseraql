package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.studio.StudioService;
import io.tesseraql.yaml.scaffold.CatalogIntrospector;
import io.tesseraql.yaml.scaffold.CatalogSchema;
import io.tesseraql.yaml.scaffold.TableIntrospector;
import io.tesseraql.yaml.scaffold.TableSchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Function;
import javax.sql.DataSource;

/**
 * Introspects the dev datasource and generates a table's CRUD slice for Studio (Studio backlog B3 —
 * "scaffold CRUD from a table" in the explorer). It reuses the same v3 schema introspection
 * ({@link CatalogIntrospector}/{@link TableIntrospector}) and CRUD generator the CLI {@code scaffold
 * crud} command does, so a slice generated from Studio is byte-identical to the command-line one.
 *
 * <p>Gated (decided with the maintainer): enabled only when Studio is writable and
 * {@code tesseraql.studio.scaffold.enabled} is set. Studio itself stays database-free — this runtime
 * service owns the connection: it introspects (read-only JDBC metadata, on its own short-lived
 * connection) and hands the resulting {@link TableSchema} to the database-free
 * {@link StudioService#scaffoldPreview} for generation.
 */
final class StudioScaffoldService {

    private static final TqlErrorCode INTROSPECT_ERROR = new TqlErrorCode(TqlDomain.STUDIO, 4223);

    private final Function<String, DataSource> datasources;
    private final String datasource;
    private final StudioService studio;
    private final boolean enabled;

    StudioScaffoldService(Function<String, DataSource> datasources, String datasource,
            StudioService studio, boolean enabled) {
        this.datasources = datasources;
        this.datasource = datasource;
        this.studio = studio;
        this.enabled = enabled;
    }

    boolean isEnabled() {
        return enabled;
    }

    /**
     * The dev datasource's introspected catalog for the table picker, or {@code null} when
     * scaffolding is disabled (the view renders the disabled state).
     */
    CatalogSchema tables() {
        if (!enabled) {
            return null;
        }
        try (Connection connection = datasources.apply(datasource).getConnection()) {
            return new CatalogIntrospector().introspect(connection);
        } catch (SQLException ex) {
            throw new TqlException(INTROSPECT_ERROR,
                    "Failed to read the database catalog: " + ex.getMessage());
        }
    }

    /**
     * The scaffold preview for one table (its generated CRUD files and their apply disposition), or
     * {@code null} when scaffolding is disabled.
     */
    StudioService.ScaffoldPreview preview(String table) {
        if (!enabled) {
            return null;
        }
        return studio.scaffoldPreview(introspect(table));
    }

    /**
     * Writes one table's scaffolded CRUD slice into the app home (honoring edit detection, or
     * overwriting when {@code force}), or {@code null} when scaffolding is disabled. The database-free
     * {@link StudioService#scaffoldApply} does the writing; this service only supplies the introspected
     * {@link TableSchema}.
     */
    StudioService.ScaffoldResult apply(String table, boolean force, String actor) {
        if (!enabled) {
            return null;
        }
        return studio.scaffoldApply(introspect(table), force, actor);
    }

    private TableSchema introspect(String table) {
        try (Connection connection = datasources.apply(datasource).getConnection()) {
            return new TableIntrospector().introspect(connection, table);
        } catch (SQLException ex) {
            throw new TqlException(INTROSPECT_ERROR,
                    "Failed to introspect table '" + table + "': " + ex.getMessage());
        }
    }
}
