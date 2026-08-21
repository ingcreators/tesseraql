package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.files.ColumnMapping;
import io.tesseraql.core.files.FileCodecs;
import io.tesseraql.core.files.FileTransferService;
import io.tesseraql.core.files.FileWriteSpec;
import io.tesseraql.core.spool.FileTempStore;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.operations.batch.JobRepository;
import io.tesseraql.operations.files.CsvFileCodec;
import io.tesseraql.operations.files.JdbcFileTransferService;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The asynchronous export path streams its extraction (docs/export-pipeline.md, decision 5). It
 * turned auto-commit off but never set a fetch size, so PostgreSQL read the whole result into the
 * client before the first row reached the row iterator — the iterator streamed over a result set
 * that was already in memory, where no codec choice or row cap could reach it.
 *
 * <p>Asserted through a recording JDBC proxy rather than a heap measurement: the properties that
 * make a driver open a cursor are exactly "auto-commit off" and "a fetch size set on a
 * forward-only statement", and the follow-up statement must run only once the result set is
 * closed, because MySQL's {@link Integer#MIN_VALUE} fetch size leaves the connection unusable for
 * anything else until then.
 */
@Testcontainers
class ExportStreamingProfileIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static DataSource dataSource;

    @BeforeAll
    static void prepare() throws Exception {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("create table export_source (id int primary key, name text,"
                    + " extracted boolean not null default false)");
            statement.execute("insert into export_source (id, name) values"
                    + " (1, 'alpha'), (2, 'beta'), (3, 'gamma')");
        }
    }

    @Test
    void theExtractionStreamsAndTheFollowUpRunsOnceTheResultSetIsClosed() throws Exception {
        List<String> calls = new ArrayList<>();
        DataSource extraction = record(dataSource, calls);

        JobRepository jobs = new JobRepository(dataSource);
        jobs.ensureSchema();
        Path spoolDir = Files.createTempDirectory("export-streaming-spool");
        JdbcFileTransferService transfers = new JdbcFileTransferService(jobs,
                new FileTempStore(spoolDir), dataSource, FileCodecs.of(new CsvFileCodec()));
        transfers.ensureSchema();

        FileTransferService.InlineResult result = transfers.exportInline(
                new FileTransferService.InlineExport(
                        "items.dump", "app", "csv",
                        new FileWriteSpec(List.of(ColumnMapping.of("id"), ColumnMapping.of("name")),
                                null, null, null),
                        "items.csv",
                        sql("select id, name from export_source order by id"),
                        sql("update export_source set extracted = true"),
                        io.tesseraql.core.files.ExportRowCap.unbounded(), java.util.Map.of()),
                extraction);

        assertThat(result.rows()).isEqualTo(3);

        // The whole point: a fetch size, from the PostgreSQL streaming profile.
        assertThat(calls).contains("setFetchSize(1000)");

        // And in the order that makes it a cursor rather than a hint.
        assertThat(indexOf(calls, "setAutoCommit(false)"))
                .as("auto-commit must be off before the extraction statement is prepared")
                .isLessThan(indexOf(calls, "setFetchSize(1000)"));
        assertThat(indexOf(calls, "setFetchSize(1000)"))
                .isLessThan(indexOf(calls, "executeQuery"));

        // The MySQL constraint, stated as a rule the code must keep: nothing else may run on the
        // connection until the streamed result set is closed.
        assertThat(indexOf(calls, "closeResultSet"))
                .as("the after: extract statement must not run while the cursor is open")
                .isLessThan(indexOf(calls, "executeUpdate"));
    }

    private static int indexOf(List<String> calls, String call) {
        int index = calls.indexOf(call);
        assertThat(index).as("expected a %s call in %s", call, calls).isNotNegative();
        return index;
    }

    private static BoundSql sql(String statement) {
        return SqlRenderer.render(Sql2WayParser.parse(statement), Map.of());
    }

    /** The datasource, with every JDBC call this test cares about appended to {@code calls}. */
    private static DataSource record(DataSource target, List<String> calls) {
        return (DataSource) Proxy.newProxyInstance(
                ExportStreamingProfileIntegrationTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, new Recorder(target, calls));
    }

    private record Recorder(Object target, List<String> calls) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result;
            try {
                result = method.invoke(target, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause();
            }
            switch (method.getName()) {
                case "setAutoCommit" -> calls.add("setAutoCommit(" + args[0] + ")");
                case "setFetchSize" -> calls.add("setFetchSize(" + args[0] + ")");
                case "executeQuery" -> calls.add("executeQuery");
                case "executeUpdate" -> calls.add("executeUpdate");
                case "close" -> {
                    if (target instanceof ResultSet) {
                        calls.add("closeResultSet");
                    }
                }
                default -> {
                    // Everything else passes through unrecorded.
                }
            }
            return wrap(result, calls);
        }

        private static Object wrap(Object result, List<String> calls) {
            Class<?> face = switch (result) {
                case null -> null;
                case ResultSet _ -> ResultSet.class;
                case PreparedStatement _ -> PreparedStatement.class;
                case Connection _ -> Connection.class;
                default -> null;
            };
            return face == null
                    ? result
                    : Proxy.newProxyInstance(face.getClassLoader(), new Class<?>[]{face},
                            new Recorder(result, calls));
        }
    }

    /** A {@link DataSource} over {@link DriverManager}; the runtime's pool is not the subject. */
    private record DriverManagerDataSource(String url, String user, String password)
            implements
                DataSource {

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }

        @Override
        public Connection getConnection(String username, String pwd) throws SQLException {
            return DriverManager.getConnection(url, username, pwd);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            // No logging surface; the driver's own is enough for a test.
        }

        @Override
        public void setLoginTimeout(int seconds) {
            // DriverManager's global timeout is not this datasource's to set.
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> face) {
            return face.cast(this);
        }

        @Override
        public boolean isWrapperFor(Class<?> face) {
            return face.isInstance(this);
        }
    }
}
