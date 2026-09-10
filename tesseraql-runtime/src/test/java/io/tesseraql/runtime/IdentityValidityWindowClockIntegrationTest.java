package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.identity.DefaultIdentityPack;
import io.tesseraql.identity.IdentityContracts;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.RealmConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * A validity window is evaluated on the APPLICATION's clock, on a database whose clock is not it.
 *
 * <p>The pack's window predicates compared {@code starts_at}/{@code ends_at} — {@code datetime}
 * columns, written from this JVM, carrying no zone — against {@code current_timestamp}, which is
 * the DATABASE's clock. Every identity test in the repository runs on PostgreSQL, where pgjdbc
 * pins the session zone and the two agree; on MySQL, MariaDB and SQL Server they are whatever
 * each host was configured with, and a mismatch breaks the comparison in both directions.
 *
 * <p>Both directions are asserted because only one of them is loud. A live grant that stops
 * resolving is an outage someone reports. A grant that ENDED and keeps resolving is silent, and
 * docs/access-governance.md makes this predicate the sole enforcement of expiry — no sweeper
 * deletes or updates an expired row, it is simply supposed to stop matching.
 *
 * <p>Every existing identity fixture grants roles with {@code starts_at}/{@code ends_at} left
 * NULL. An unbounded grant has no window to shift, so it cannot see this defect in either
 * direction — and it equally cannot see the regression the fix can introduce, where a contract
 * whose {@code now} is never seeded renders {@code ends_at > NULL} and drops every WINDOWED grant
 * while unbounded ones keep resolving. {@link #anUnboundedGrantIsUnaffectedInEveryWorld} is that
 * control, and it is the reason the other two seed real windows. Both regressions were built and
 * run against this test before it was trusted.
 */
@Testcontainers
class IdentityValidityWindowClockIntegrationTest {

    /**
     * Two databases, because ONE OFFSET ONLY SEES ONE DIRECTION and the first version of this test
     * proved it. With the database east of the JVM every window looks expired: the live grant
     * vanishes (the loud half, caught) but the expired grant also fails to resolve — the right
     * answer for the wrong reason, so that assertion passed against the unfixed code and would
     * have shipped certifying a defect it never exercised. The silent half only appears when the
     * database is WEST, where an ended window has not ended yet.
     */
    @Container
    @SuppressWarnings("resource") // lifecycle is managed by the @Container extension
    static final MySQLContainer EAST = new MySQLContainer("mysql:8.0")
            .withCommand("--default-time-zone=+09:00");

    @Container
    @SuppressWarnings("resource") // lifecycle is managed by the @Container extension
    static final MySQLContainer WEST = new MySQLContainer("mysql:8.0")
            .withCommand("--default-time-zone=-09:00");

    private static final DateTimeFormatter SQL_TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss");

    private static IdentityService eastward;
    private static IdentityService westward;
    private static final RealmConfig REALM = RealmConfig.managed("local", "main");

    @BeforeAll
    static void setUp() throws Exception {
        DataSource east = dataSource(EAST);
        DataSource west = dataSource(WEST);
        eastward = new IdentityService((Function<String, DataSource>) name -> east);
        westward = new IdentityService((Function<String, DataSource>) name -> west);
        seed(east);
        seed(west);
    }

    /**
     * The instrument before the measurement: if the container ignored {@code --default-time-zone}
     * the two clocks would agree, every assertion below would pass against the unfixed code, and
     * the suite would report a defect it never exercised.
     */
    @Test
    void bothDatabaseClocksGenuinelyDisagreeWithThisJvm() throws SQLException {
        Instant jvm = Instant.now();
        LocalDateTime here = jvm.atZone(ZoneOffset.UTC).toLocalDateTime();

        assertThat(Duration.between(here, databaseClock(EAST)).toMinutes())
                .as("the eastward database must LEAD this JVM by ~9h, or the fail-closed "
                        + "assertion cannot see the defect it exists for")
                .isBetween(530L, 550L);
        assertThat(Duration.between(here, databaseClock(WEST)).toMinutes())
                .as("the westward database must TRAIL this JVM by ~9h, or the fail-open "
                        + "assertion is green on the defect")
                .isBetween(-550L, -530L);
    }

    /**
     * Fail-closed, against a database whose clock leads: a grant that started a minute ago and
     * runs for another half hour resolves.
     */
    @Test
    void aGrantInsideItsWindowResolvesWhereTheDatabaseClockLeads() {
        List<Map<String, Object>> roles = eastward.execute(REALM,
                IdentityContracts.FIND_ROLES_BY_USER_ID, Map.of("userId", "u-live"));

        assertThat(roles).extracting(row -> row.get("role_code"))
                .as("a live windowed grant must resolve; on the database's clock its ends_at is "
                        + "already nine hours in the past")
                .containsExactly("LIVE_ROLE");
    }

    /**
     * Fail-open, against a database whose clock trails — and this is the half that matters. A
     * grant whose window closed an hour ago must not resolve; on the database's clock it has not
     * closed yet, and nothing else revokes it.
     */
    @Test
    void aGrantPastItsWindowDoesNotResolveWhereTheDatabaseClockTrails() {
        List<Map<String, Object>> roles = westward.execute(REALM,
                IdentityContracts.FIND_ROLES_BY_USER_ID, Map.of("userId", "u-expired"));

        assertThat(roles)
                .as("an expired grant kept resolving for the whole zone offset, and expiry has no "
                        + "sweeper behind it")
                .isEmpty();
    }

    /**
     * The control that proves the two tests above had to seed windows: this one passes both
     * before and after the fix, on both hosts, and it is what a forgotten {@code now} seed would
     * break.
     */
    @Test
    void anUnboundedGrantIsUnaffectedInEveryWorld() {
        for (IdentityService service : List.of(eastward, westward)) {
            assertThat(service.execute(REALM, IdentityContracts.FIND_ROLES_BY_USER_ID,
                    Map.of("userId", "u-unbounded")))
                    .extracting(row -> row.get("role_code"))
                    .containsExactly("UNBOUNDED_ROLE");
        }
    }

    private static LocalDateTime databaseClock(MySQLContainer container) throws SQLException {
        try (Connection connection = connection(container);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select current_timestamp")) {
            rows.next();
            return rows.getTimestamp(1).toLocalDateTime();
        }
    }

    /**
     * Windows are written the way the application writes them: a JVM instant rendered into a
     * zone-less column, which is exactly the value the read has to compare against.
     */
    private static void seed(DataSource dataSource) throws SQLException {
        Instant now = Instant.now();
        String startedAMinuteAgo = sqlTimestamp(now.minus(Duration.ofMinutes(1)));
        String endsInHalfAnHour = sqlTimestamp(now.plus(Duration.ofMinutes(30)));
        // The expired window must STRADDLE the skew, not sit inside it. A grant that both
        // started and ended within the last nine hours is excluded by the trailing host for the
        // wrong reason — its starts_at looks like the future — and the fail-open assertion then
        // passes against the unfixed code. Twelve hours back is before the trailing clock's
        // "now"; one hour back is still ahead of it.
        String startedTwelveHoursAgo = sqlTimestamp(now.minus(Duration.ofHours(12)));
        String endedAnHourAgo = sqlTimestamp(now.minus(Duration.ofHours(1)));

        runScript(dataSource, DefaultIdentityPack.schema("mysql"));
        runScript(dataSource, """
                insert into tql_users (user_id, login_id, display_name, status) values
                  ('u-live','live','Live','ACTIVE'),
                  ('u-expired','expired','Expired','ACTIVE'),
                  ('u-unbounded','unbounded','Unbounded','ACTIVE');
                insert into tql_roles (role_id, role_code, role_name) values
                  ('r-live','LIVE_ROLE','Live'),
                  ('r-expired','EXPIRED_ROLE','Expired'),
                  ('r-unbounded','UNBOUNDED_ROLE','Unbounded');
                insert into tql_user_roles (user_id, role_id, starts_at, ends_at) values
                  ('u-live','r-live','%s','%s'),
                  ('u-expired','r-expired','%s','%s'),
                  ('u-unbounded','r-unbounded',null,null);
                """.formatted(startedAMinuteAgo, endsInHalfAnHour,
                startedTwelveHoursAgo, endedAnHourAgo));
    }

    private static String sqlTimestamp(Instant instant) {
        return SQL_TIMESTAMP.format(instant.atZone(ZoneOffset.UTC).toLocalDateTime());
    }

    private static void runScript(DataSource dataSource, String script) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (String sql : script.split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        }
    }

    private static Connection connection(MySQLContainer container) throws SQLException {
        return DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(),
                container.getPassword());
    }

    private static DataSource dataSource(MySQLContainer container) {
        com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl(container.getJdbcUrl());
        config.setUsername(container.getUsername());
        config.setPassword(container.getPassword());
        return new com.zaxxer.hikari.HikariDataSource(config);
    }
}
