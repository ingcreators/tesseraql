package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.dialect.Dialect;
import io.tesseraql.core.dialect.StreamingProfile;
import io.tesseraql.core.dialect.StreamingProfiles;
import io.tesseraql.core.jdbc.DriverManagerDataSource;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.core.sql.SqlStatement;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.mysql.MySQLContainer;

/**
 * A dialect's {@link StreamingProfile} must be one its own driver accepts.
 *
 * <p>{@link io.tesseraql.core.sql.SqlStatementTest} already asserts that the profile's fetch size
 * reaches the driver, but it asserts that against a fake, which accepts every value. That is the
 * shape of test this class exists to complement: `mariadb` was mapped to MySQL's
 * {@code Integer.MIN_VALUE} row-streaming signal, MariaDB Connector/J answers
 * {@code SQLSyntaxErrorException: invalid fetch size}, and no test could see it because none ever
 * handed the value to a real MariaDB driver.
 *
 * <p>Both containers are small and ungated, like {@link MySqlPortabilityIntegrationTest} — the
 * gate at {@code -Dtesseraql.dialect.its=true} is for the large Oracle and SQL Server images.
 */
@Testcontainers
class StreamingProfileDriverIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0");

    @Container
    static final MariaDBContainer MARIADB = new MariaDBContainer("mariadb:11.4");

    @Test
    void mysqlStreamsWithTheProfileItIsGiven() throws Exception {
        assertStreamsFiftyRows(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    @Test
    void mariadbStreamsWithTheProfileItIsGiven() throws Exception {
        assertStreamsFiftyRows(MARIADB.getJdbcUrl(), MARIADB.getUsername(),
                MARIADB.getPassword());
    }

    /**
     * Seeds fifty rows, then reads them back through the framework's own primitive at exactly the
     * fetch size an export would use — the call {@code SqlStep.export} makes.
     *
     * <p>The dialect id is derived the way {@code RouteCompiler.datasourceDialect} derives it, from
     * the JDBC URL, rather than named here. That is the whole point: a `jdbc:mariadb://` URL infers
     * {@code Dialect.MYSQL}, so a test that passed the literal {@code "mariadb"} would exercise a
     * branch no unconfigured deployment reaches, and would pass while the real path stayed broken.
     */
    private static void assertStreamsFiftyRows(String url, String user, String password)
            throws Exception {
        String dialect = Dialect.fromJdbcUrl(url).map(Dialect::id).orElse("");
        DataSource dataSource = new DriverManagerDataSource(url, user, password);
        try (Connection setup = dataSource.getConnection();
                Statement statement = setup.createStatement()) {
            statement.execute("drop table if exists streaming_rows");
            statement.execute("create table streaming_rows (id int primary key, v varchar(20))");
            for (int i = 1; i <= 50; i++) {
                statement.execute("insert into streaming_rows values (" + i + ", 'v" + i + "')");
            }
        }

        StreamingProfile profile = StreamingProfiles.forDialect(dialect);
        BoundSql bound = SqlRenderer.render("select id, v from streaming_rows order by id",
                Map.of());
        int rows = SqlStatement.on(dataSource).fetchSize(profile.fetchSize())
                .read("web/api/export.sql", bound, (resultSet, span) -> {
                    int seen = 0;
                    while (resultSet.next()) {
                        seen++;
                    }
                    return seen;
                });

        assertThat(rows)
                .as("%s (dialect %s) streams every row at fetch size %d", url, dialect,
                        profile.fetchSize())
                .isEqualTo(50);
    }
}
