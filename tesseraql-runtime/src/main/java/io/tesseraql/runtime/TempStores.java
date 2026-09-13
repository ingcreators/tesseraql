package io.tesseraql.runtime;

import io.tesseraql.core.blob.BlobStore;
import io.tesseraql.core.blob.FileBlobStore;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.spool.BlobTempStore;
import io.tesseraql.core.spool.FileTempStore;
import io.tesseraql.core.spool.TempStore;
import io.tesseraql.core.util.Sizes;
import io.tesseraql.operations.spool.JdbcTempStore;
import io.tesseraql.yaml.blob.BlobStores;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.config.WorkHome;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one reading of {@code tesseraql.temp.store} (docs/deployment.md "Shared export files";
 * docs/export-hygiene.md): file (node-local, the default), db (the main database — any node
 * serves the download) or blob (the configured object store). The served runtime and the CLI job
 * runner both build their store here, so a step run from a scheduler's shell records a spool the
 * served nodes can download and the retention sweep can free. The CLI used to build the file
 * store whatever the app declared, and left {@code file:///} references in the shared table.
 */
public final class TempStores {

    private static final Logger LOG = LoggerFactory.getLogger(TempStores.class);

    private TempStores() {
    }

    /**
     * The node-local scratch directory: the file store's home, the database store's staging area
     * and the request-body upload spool. Through WorkHome rather than by spelling the conventional
     * layout against the app home: {@code tesseraql.app.work} is "honored everywhere or nowhere"
     * by that class's own contract, and a relocation key that moved the temp store but not the
     * upload spool would move half a subsystem. WorkHomeLedgerTest holds the rest of that class.
     */
    public static Path scratch(AppConfig config, Path appHome) {
        return WorkHome.resolve(appHome, config).resolve("tmp/tesseraql");
    }

    /**
     * Builds the declared store, creating the database store's table when that is the choice.
     *
     * @param config     the application configuration
     * @param appHome    the application home, the root the work directory is resolved against
     * @param scratch    the node-local scratch directory, {@link #scratch}
     * @param loader     the class loader an object-storage provider module is looked up through
     * @param dataSource the main datasource, the database store's home
     */
    public static TempStore create(AppConfig config, Path appHome, Path scratch,
            ClassLoader loader, DataSource dataSource) {
        String kind = config.getString("tesseraql.temp.store").orElse("file");
        return switch (kind) {
            case "file" -> new FileTempStore(scratch);
            case "db" -> {
                JdbcTempStore jdbcTemp = new JdbcTempStore(dataSource, scratch,
                        config.getString("tesseraql.temp.maxBytes")
                                .map(value -> Sizes.parseBytes(value, "tesseraql.temp.maxBytes"))
                                .orElse(JdbcTempStore.DEFAULT_MAX_BYTES));
                jdbcTemp.ensureSchema();
                yield jdbcTemp;
            }
            case "blob" -> {
                BlobStore blobStore = BlobStores.create(config, appHome, loader);
                if (blobStore instanceof FileBlobStore) {
                    LOG.warn("tesseraql.temp.store: blob with the local file provider is still"
                            + " node-local; configure tesseraql.object-storage.provider (or use"
                            + " store: db) for multi-node downloads");
                }
                yield new BlobTempStore(blobStore,
                        config.getString("tesseraql.temp.bucket").orElse("tesseraql-temp"));
            }
            default -> throw new TqlException(new TqlErrorCode(TqlDomain.YAML, 1024),
                    "tesseraql.temp.store must be 'file', 'db', or 'blob', got '" + kind + "'");
        };
    }
}
