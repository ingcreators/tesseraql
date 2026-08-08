package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.decision.DecisionTables;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.decision.DecisionSets;
import io.tesseraql.yaml.model.DecisionsDocument;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The generated lookup of a table-backed decision against a real database
 * (docs/decision-tables.md "Evaluation"): NULL cells are wildcards, {@code in} membership
 * rides the child table, {@code subtree} the org closure, dated rows the effective window;
 * {@code first} resolves by priority, a miss without a default raises, and {@code unique}
 * ambiguity raises.
 */
@Testcontainers
class DecisionTableSourceIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @BeforeAll
    static void seed() throws Exception {
        try (Connection connection = connect(); Statement ddl = connection.createStatement()) {
            ddl.execute("""
                    create table fee_rules (
                      id bigint primary key,
                      dept_unit varchar(20),
                      region varchar(20),
                      weight_min numeric,
                      weight_max numeric,
                      valid_from timestamp,
                      valid_to timestamp,
                      priority int not null,
                      fee numeric not null,
                      carrier varchar(20) not null)
                    """);
            ddl.execute("create table fee_rule_categories ("
                    + "rule_id bigint not null, category varchar(30) not null)");
            ddl.execute("create table tql_org_closure ("
                    + "ancestor_id varchar(40), descendant_id varchar(40), depth int)");
            // U1 ⊃ U2; sales-east sits under U2.
            ddl.execute("insert into tql_org_closure values"
                    + " ('U1','U1',0), ('U1','U2',1), ('U2','U2',0)");
            // Rule 1: office-supplies/books, light, east region, U2 subtree, current year.
            ddl.execute("insert into fee_rules values"
                    + " (1, 'U2', 'east', 0, 10, timestamp '2026-01-01 00:00:00',"
                    + " timestamp '2026-12-31 23:59:59', 10, 500, 'bike')");
            ddl.execute("insert into fee_rule_categories values"
                    + " (1, 'office-supplies'), (1, 'books')");
            // Rule 2: the wildcard fallback row — every cell NULL, lowest priority.
            ddl.execute("insert into fee_rules values"
                    + " (2, null, null, null, null, null, null, 90, 1500, 'truck')");
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    private static DecisionsDocument.Decision decision(String hitPolicy,
            Map<String, Object> defaultOut) {
        Map<String, DecisionsDocument.Input> inputs = new LinkedHashMap<>();
        inputs.put("category", new DecisionsDocument.Input("string", null, "in"));
        inputs.put("weight", new DecisionsDocument.Input("number", null, "between"));
        inputs.put("region", new DecisionsDocument.Input("string", null, null));
        inputs.put("dept", new DecisionsDocument.Input("string", null, "subtree"));
        Map<String, DecisionsDocument.Output> outputs = new LinkedHashMap<>();
        outputs.put("fee", new DecisionsDocument.Output("number", null, null));
        outputs.put("carrier", new DecisionsDocument.Output("string", null, null));
        Map<String, DecisionsDocument.ColumnMatch> match = new LinkedHashMap<>();
        match.put("weight", new DecisionsDocument.ColumnMatch(null,
                List.of("weight_min", "weight_max"), null));
        match.put("region", new DecisionsDocument.ColumnMatch("region", null, null));
        match.put("dept", new DecisionsDocument.ColumnMatch(null, null, "dept_unit"));
        Map<String, String> outputColumns = new LinkedHashMap<>();
        outputColumns.put("fee", "fee");
        outputColumns.put("carrier", "carrier");
        return new DecisionsDocument.Decision(inputs, outputs, hitPolicy, null, null,
                new DecisionsDocument.Source("fee_rules", null, match,
                        Map.of("category", new DecisionsDocument.SetMatch("fee_rule_categories",
                                "rule_id", "category")),
                        "priority", List.of("valid_from", "valid_to"), outputColumns),
                defaultOut);
    }

    private static Map<String, Object> lookup(DecisionsDocument.Decision decision,
            Map<String, Object> inputs, Object effectiveAt) throws Exception {
        DecisionTables.TableSource source = DecisionSets.compileSource("shippingFee", decision,
                "postgres");
        try (Connection connection = connect()) {
            return source.evaluate(connection, inputs, effectiveAt, 5);
        }
    }

    private static final Timestamp IN_2026 = Timestamp.from(Instant.parse("2026-07-30T00:00:00Z"));

    @Test
    void aFullyConstrainedRowMatchesThroughSetSubtreeRangeAndWindow() throws Exception {
        Map<String, Object> hit = lookup(decision("first", null), Map.of(
                "category", "books", "weight", 5, "region", "east", "dept", "U2"), IN_2026);

        assertThat(hit.get("carrier")).isEqualTo("bike");
    }

    @Test
    void subtreeMembershipRidesTheClosureNotEquality() throws Exception {
        // The caller sits in U2, the rule names U2's subtree: a caller outside it falls through
        // to the wildcard row.
        Map<String, Object> outside = lookup(decision("first", null), Map.of(
                "category", "books", "weight", 5, "region", "east", "dept", "U9"), IN_2026);

        assertThat(outside.get("carrier")).isEqualTo("truck");
    }

    @Test
    void anExpiredWindowFallsThroughToTheWildcardRow() throws Exception {
        Map<String, Object> expired = lookup(decision("first", null), Map.of(
                "category", "books", "weight", 5, "region", "east", "dept", "U2"),
                Timestamp.from(Instant.parse("2027-06-01T00:00:00Z")));

        assertThat(expired.get("carrier")).isEqualTo("truck");
    }

    @Test
    void aMissWithoutADefaultRaisesAndADeclaredDefaultAnswers() throws Exception {
        try (Connection connection = connect(); Statement ddl = connection.createStatement()) {
            ddl.execute("delete from fee_rules where id = 2");
        }
        try {
            Map<String, Object> inputs = Map.of("category", "travel", "weight", 500,
                    "region", "north", "dept", "U9");

            assertThatThrownBy(() -> lookup(decision("first", null), inputs, IN_2026))
                    .isInstanceOf(TqlException.class)
                    .hasMessageContaining("TQL-DECISION-4721");
            assertThat(lookup(decision("first", Map.of("fee", 0, "carrier", "manual")),
                    inputs, IN_2026).get("carrier")).isEqualTo("manual");
        } finally {
            try (Connection connection = connect();
                    Statement ddl = connection.createStatement()) {
                ddl.execute("insert into fee_rules values"
                        + " (2, null, null, null, null, null, null, 90, 1500, 'truck')");
            }
        }
    }

    @Test
    void uniqueAmbiguityRaisesInsteadOfPickingSilently() throws Exception {
        // Both rows match a light east shipment: rule 1 and the wildcard row.
        Map<String, Object> inputs = Map.of("category", "books", "weight", 5,
                "region", "east", "dept", "U2");

        assertThatThrownBy(() -> lookup(decision("unique", null), inputs, IN_2026))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-DECISION-4720");
    }
}
