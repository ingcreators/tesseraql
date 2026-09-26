package io.tesseraql.core.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What each driver is given (docs/connection-liveness.md decisions 1, 2 and 6): the labels, the
 * name every driver can carry — printable ASCII without {@code :} or {@code ,}, at most 63 bytes
 * — each driver's keys, and the rule that a key the URL declares is never added.
 */
class ConnectionPropertiesTest {

    private static final String LABEL = "tesseraql/orders/main";

    @Test
    void aPoolIsLabelledByItsApplicationAndItsPool() {
        assertThat(ConnectionProperties.poolLabel("orders", "tesseraql-main"))
                .isEqualTo("tesseraql/orders/main");
        assertThat(ConnectionProperties.poolLabel("orders", "tesseraql-main-jobs"))
                .isEqualTo("tesseraql/orders/main-jobs");
        assertThat(ConnectionProperties.poolLabel("orders", "tesseraql-tenant-acme"))
                .isEqualTo("tesseraql/orders/tenant-acme");
        assertThat(ConnectionProperties.poolLabel(null, "tesseraql-stack-framework"))
                .as("a pool the stack owns names no application")
                .isEqualTo("tesseraql/stack-framework");
        assertThat(ConnectionProperties.jobRunLabel("orders"))
                .isEqualTo("tesseraql/orders/job-run");
    }

    @Test
    void aCharacterOutsidePrintableAsciiIsPercentEncodedAsUtf8() {
        assertThat(ConnectionProperties.applicationName("tesseraql/受注/main"))
                .isEqualTo("tesseraql/%E5%8F%97%E6%B3%A8/main");
    }

    /** An application name may contain both; {@code connectionAttributes} separates by them. */
    @Test
    void aColonAndACommaAreEncodedToo() {
        assertThat(ConnectionProperties.applicationName("tesseraql/a:b,c/main"))
                .isEqualTo("tesseraql/a%3Ab%2Cc/main");
    }

    @Test
    void aLongLabelIsCutAt63BytesAtACharacterBoundary() {
        assertThat(ConnectionProperties.applicationName("x".repeat(80))).hasSize(63);

        // 60 bytes, then a character whose escape is nine: it does not fit, and is not split.
        String cut = ConnectionProperties.applicationName("tesseraql/" + "a".repeat(50) + "受");
        assertThat(cut).isEqualTo("tesseraql/" + "a".repeat(50));
    }

    @Test
    void eachDriverIsGivenItsOwnKeys() {
        assertThat(given("jdbc:postgresql://db:5432/app")).containsExactly(
                Map.entry("ApplicationName", LABEL),
                Map.entry("tcpKeepAlive", "true"),
                Map.entry("socketFactory", KeepaliveSocketFactory.class.getName()));
        assertThat(given("jdbc:sqlserver://db:1433;databaseName=app"))
                .as("the driver keeps alive at 30 s and 1 s itself")
                .containsExactly(Map.entry("applicationName", LABEL));
        assertThat(given("jdbc:oracle:thin:@//db:1521/FREEPDB1")).containsExactly(
                Map.entry("v$session.program", LABEL),
                Map.entry("oracle.net.keepAlive", "true"),
                Map.entry("oracle.net.TCP_KEEPIDLE", "30"),
                Map.entry("oracle.net.TCP_KEEPINTERVAL", "10"),
                Map.entry("oracle.net.TCP_KEEPCOUNT", "3"));
        assertThat(given("jdbc:mariadb://db:3306/app")).containsExactly(
                Map.entry("connectionAttributes", "program_name:" + LABEL),
                Map.entry("tcpKeepIdle", "30"),
                Map.entry("tcpKeepInterval", "10"),
                Map.entry("tcpKeepCount", "3"));
        assertThat(given("jdbc:mysql://db:3306/app"))
                .as("no property sets the timings; keepalive is already on")
                .containsExactly(Map.entry("connectionAttributes", "program_name:" + LABEL));
    }

    @Test
    void anEmbeddedDatabaseIsGivenNothing() {
        assertThat(given("jdbc:h2:mem:x")).isEmpty();
        assertThat(given("jdbc:duckdb:")).isEmpty();
    }

    /**
     * MySQL's and SQL Server's drivers let a passed property override the URL's, so a key the
     * URL declares is left out — for every driver, and only that key.
     */
    @Test
    void aKeyTheUrlDeclaresIsNeverAdded() {
        assertThat(given("jdbc:sqlserver://db:1433;databaseName=app;ApplicationName=ops"))
                .as("SQL Server's form, any case").isEmpty();
        assertThat(given("jdbc:mysql://db:3306/app?connectionAttributes=team:ops")).isEmpty();
        assertThat(given("jdbc:oracle:thin:@//db:1521/FREEPDB1?oracle.net.keepAlive=false"))
                .doesNotContainKey("oracle.net.keepAlive")
                .containsKeys("v$session.program", "oracle.net.TCP_KEEPIDLE");
        assertThat(given("jdbc:postgresql://db:5432/app?sslmode=require&tcpKeepAlive=false"))
                .doesNotContainKey("tcpKeepAlive")
                .containsKeys("ApplicationName", "socketFactory");
    }

    private static Map<String, String> given(String jdbcUrl) {
        Map<String, String> given = new LinkedHashMap<>();
        ConnectionProperties.apply(jdbcUrl, LABEL, given::put);
        return given;
    }
}
