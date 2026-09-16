package io.tesseraql.yaml.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.ColumnValueException;
import io.tesseraql.yaml.model.Binding;
import io.tesseraql.yaml.model.InputField;
import io.tesseraql.yaml.model.RouteDefinition;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The one predicate both sides judge a declared {@code type:} by, and the read that applies a
 * {@code result:} entry (docs/temporal-semantics.md T3).
 */
class DeclaredKindsTest {

    // ---- the vocabulary, per surface ---------------------------------------------------------

    @Test
    void jsonIsRefusedOnAnInputAndSaysWhereItBelongs() {
        RouteDefinition route = route(Map.of("payload", field("json", null, null)), Map.of());

        List<DeclaredKinds.Violation> violations = DeclaredKinds.inputViolations(route);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).key()).isEqualTo("input.payload.type");
        assertThat(violations.get(0).message())
                .contains("route 'r' input.payload.type: 'json' is not a type a request binds")
                .contains("a result: declaration parses");
    }

    @Test
    void anUnknownInputTypeIsRefusedInsteadOfBoundAsText() {
        RouteDefinition route = route(Map.of("qty", field("integr", null, null)), Map.of());

        assertThat(DeclaredKinds.inputViolations(route)).singleElement()
                .extracting(DeclaredKinds.Violation::message).asString()
                .contains("'integr' is not a type a request binds")
                .doesNotContain("json is a kind");
    }

    @Test
    void everyInputTypeARequestBindsIsAccepted() {
        for (String type : DeclaredKinds.INPUT_TYPES) {
            assertThat(DeclaredKinds.inputViolations(
                    route(Map.of("f", field(type, null, null)), Map.of())))
                    .as(type).isEmpty();
        }
        assertThat(DeclaredKinds.INPUT_TYPES).doesNotContain("json");
    }

    @Test
    void aResultKindOutsideTheFourIsRefused() {
        RouteDefinition route = route(Map.of(),
                Map.of("main", source(Map.of("flag", field("boolean", null, null)))));

        assertThat(DeclaredKinds.resultViolations(route)).singleElement()
                .satisfies(violation -> {
                    assertThat(violation.key()).isEqualTo("sources.main.result.flag.type");
                    assertThat(violation.message())
                            .contains("'boolean' is not a kind a result: declaration parses")
                            .contains("date, datetime, json, number");
                });
    }

    @Test
    void aFormatTheKindsParserRefusesIsRefusedAndJsonReadsNone() {
        RouteDefinition route = route(Map.of(), Map.of("main", source(Map.of(
                "d", field("date", "yyyy-bb-dd", null),
                "n", field("number", "#,##0.00", null),
                "j", field("json", "yyyy", null)))));

        List<DeclaredKinds.Violation> violations = DeclaredKinds.resultViolations(route);

        assertThat(violations).extracting(DeclaredKinds.Violation::key)
                .containsExactlyInAnyOrder("sources.main.result.d.format",
                        "sources.main.result.j.format");
        assertThat(violations).extracting(DeclaredKinds.Violation::message)
                .anySatisfy(message -> assertThat(message)
                        .contains("a json declaration reads no format:"));
    }

    @Test
    void aDeclarationOnABindingThatPublishesNoRowsIsRefused() {
        Binding update = new Binding("write.sql", null, "update", null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                Map.of("id", field("number", null, null)));
        RouteDefinition route = route(Map.of(), Map.of("header", update));

        assertThat(DeclaredKinds.resultViolations(route)).singleElement()
                .extracting(DeclaredKinds.Violation::message).asString()
                .contains("route 'r' steps.header.result:")
                .contains("publishes none (mode: update)");
    }

    /**
     * The export recipes hand every source to the writer, which reads through {@code columns:}
     * and applies no declaration (docs/audit-low-leads.md, the {@code result:} sweep): a
     * {@code result:} there used to lint clean and boot, and the csv carried the raw text.
     */
    @Test
    void aDeclarationOnAnExportRecipeIsRefusedNamingTheRecipe() {
        for (String recipe : List.of("query-export", "file-export")) {
            RouteDefinition route = route(recipe, Map.of(),
                    Map.of("main", source(Map.of("note", field("json", null, null)))));

            assertThat(DeclaredKinds.resultViolations(route)).as(recipe).singleElement()
                    .extracting(DeclaredKinds.Violation::message).asString()
                    .contains("route 'r' sources.main.result:")
                    .contains(recipe + " hands every source to the export writer");
        }
        assertThat(DeclaredKinds.resultViolations(route("query-json", Map.of(),
                Map.of("main", source(Map.of("note", field("json", null, null))))))).isEmpty();
    }

    /**
     * A {@code result:} on a chunk reader or writer is the lint's error and, from the same
     * predicate, the job registration's refusal (docs/audit-low-leads.md TS-03): {@code
     * requireJob} used to return normally, the run reported COMPLETED, and a writer navigating
     * the un-parsed text wrote NULL.
     */
    @Test
    void aDeclarationOnAChunkReaderIsRefusedWhereTheJobRegisters(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Files.createDirectories(dir.resolve("config"));
        java.nio.file.Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n");
        java.nio.file.Files.createDirectories(dir.resolve("batch/load"));
        java.nio.file.Files.writeString(dir.resolve("batch/load/job.yml"), """
                version: tesseraql/v1
                id: nightly
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: load
                    chunk:
                      reader:
                        sql:
                          file: read.sql
                        result:
                          payload: { type: json }
                      writer:
                        sql:
                          file: write.sql
                """);
        java.nio.file.Files.writeString(dir.resolve("batch/load/read.sql"),
                "select id, payload from src order by id\n");
        java.nio.file.Files.writeString(dir.resolve("batch/load/write.sql"),
                "insert into dst (id) values (/* row.id */1)\n");
        io.tesseraql.yaml.manifest.JobFile job = new io.tesseraql.yaml.manifest.ManifestLoader()
                .load(dir).jobs().get(0);

        assertThat(DeclaredKinds.chunkViolations(job.definition())).singleElement()
                .satisfies(violation -> {
                    assertThat(violation.key()).isEqualTo("chunk.reader.result");
                    assertThat(violation.message()).contains("job 'nightly' step 'load'",
                            "chunk.reader.result: is not applied");
                });
        List<String> warned = new java.util.ArrayList<>();
        assertThatThrownBy(() -> ExportDeclarations.requireJob("t", dir, job, warned::add))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1064")
                .hasMessageContaining("chunk.reader.result: is not applied");
        assertThat(warned).hasSize(1);
    }

    /** Decision 23: an input parses in the request's locale, so locale: written there is refused. */
    @Test
    void aLocaleWrittenOnAnInputIsRefused() {
        RouteDefinition route = route(
                Map.of("amount", withLocale(field("number", "#,##0.00", null), "de-DE")), Map.of());

        assertThat(DeclaredKinds.inputViolations(route)).singleElement()
                .satisfies(violation -> {
                    assertThat(violation.key()).isEqualTo("input.amount.locale");
                    assertThat(violation.message())
                            .contains("locale: is not applied on an input")
                            .contains("declare it on the result: entry or the domain");
                });
    }

    /** Decision 23: a result: entry's locale is judged like export.locale. */
    @Test
    void aLocaleTheJdkCannotFormatIsRefusedOnAResultEntry() {
        RouteDefinition route = route(Map.of(), Map.of("main", source(Map.of(
                "ok", withLocale(field("number", "#,##0.00", null), "de-DE"),
                "bad", withLocale(field("number", "#,##0.00", null), "ja_JP")))));

        assertThat(DeclaredKinds.resultViolations(route)).singleElement()
                .satisfies(violation -> {
                    assertThat(violation.key()).isEqualTo("sources.main.result.bad.locale");
                    assertThat(violation.message())
                            .contains("'ja_JP' is not a language tag the JDK can format");
                });
    }

    /**
     * Decision 24: a read says what the column's text is, not what it may be — every key
     * outside the read keys, written on the entry, is refused and named.
     */
    @Test
    void aConstraintOrOperationalKeyWrittenOnAResultEntryIsRefused() {
        InputField written = new InputField("number", true, "0", new BigDecimal("1"),
                new BigDecimal("9"), 5, List.of("a"), Boolean.FALSE, "personal", "fixed",
                "#,##0.00", null, "[0-9]+", 1, "x > 1", List.of("c"), null, "code", "cat",
                "inv.write", "a description", null, "de-DE");
        RouteDefinition route = route(Map.of(), Map.of("main", source(Map.of("n", written))));

        List<DeclaredKinds.Violation> violations = DeclaredKinds.resultViolations(route);

        assertThat(violations).extracting(DeclaredKinds.Violation::key)
                .containsExactlyInAnyOrder("sources.main.result.n.required",
                        "sources.main.result.n.requiredWhen", "sources.main.result.n.default",
                        "sources.main.result.n.writable", "sources.main.result.n.policy",
                        "sources.main.result.n.min", "sources.main.result.n.max",
                        "sources.main.result.n.minLength", "sources.main.result.n.maxLength",
                        "sources.main.result.n.pattern", "sources.main.result.n.enum",
                        "sources.main.result.n.columns", "sources.main.result.n.classification",
                        "sources.main.result.n.mask", "sources.main.result.n.widget",
                        "sources.main.result.n.codes");
        assertThat(violations.get(0).message())
                .contains("is not a key a result: declaration reads (type, format, locale,"
                        + " domain, description)");
    }

    @Test
    void requireThrowsTheFirstViolationWithItsCode() {
        RouteDefinition route = route(Map.of("payload", field("json", null, null)), Map.of());
        List<String> warned = new java.util.ArrayList<>();

        assertThatThrownBy(() -> DeclaredKinds.require(
                DeclaredKinds.inputViolations(route), warned::add))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("'json' is not a type a request binds")
                .extracting(ex -> ((TqlException) ex).code().toString())
                .isEqualTo("TQL-YAML-1064");
        assertThat(warned).hasSize(1);
    }

    // ---- the read ----------------------------------------------------------------------------

    @Test
    void jsonTextBecomesANavigableValueWhoseTextIsCompactJson() {
        Object value = DeclaredKinds.read("payload", field("json", null, null),
                "{\"sku\": \"A-1\", \"qty\": 2, \"tags\": [\"x\", 1.10], \"price\": 1.10}");

        assertThat(value).isInstanceOf(Map.class);
        Map<?, ?> payload = (Map<?, ?>) value;
        assertThat(payload.get("sku")).isEqualTo("A-1");
        assertThat(payload.get("qty")).isEqualTo(2);
        assertThat(payload.get("price")).isEqualTo(new BigDecimal("1.10"));
        assertThat(payload.get("tags")).isInstanceOf(List.class);
        // The five String.valueOf surfaces print JSON, never {sku=A-1}.
        assertThat(String.valueOf(value)).isEqualTo(
                "{\"sku\":\"A-1\",\"qty\":2,\"tags\":[\"x\",1.10],\"price\":1.10}");
        assertThat(String.valueOf(payload.get("tags"))).isEqualTo("[\"x\",1.10]");
    }

    @Test
    void aJsonScalarDocumentAndTheNullLiteralAreThemselves() {
        InputField json = field("json", null, null);

        assertThat(DeclaredKinds.read("v", json, "\"text\"")).isEqualTo("text");
        assertThat(DeclaredKinds.read("v", json, "42")).isEqualTo(42);
        assertThat(DeclaredKinds.read("v", json, "true")).isEqualTo(true);
        assertThat(DeclaredKinds.read("v", json, "null")).isNull();
        assertThat(DeclaredKinds.read("v", json, null)).isNull();
    }

    @Test
    void aContainerAnOutboundCallProducedIsRewrappedSoItsTextIsJson() {
        Object value = DeclaredKinds.read("payload", field("json", null, null),
                Map.of("sku", "A-1"));

        assertThat(value).isInstanceOf(JsonValues.JsonObject.class);
        assertThat(String.valueOf(value)).isEqualTo("{\"sku\":\"A-1\"}");
    }

    @Test
    void textThatIsNotJsonIsRefusedNamingTheColumn() {
        assertThatThrownBy(() -> DeclaredKinds.read("payload", field("json", null, null),
                "{sku: A-1"))
                .isInstanceOf(ColumnValueException.class)
                .satisfies(ex -> {
                    ColumnValueException refused = (ColumnValueException) ex;
                    assertThat(refused.column()).isEqualTo("payload");
                    assertThat(refused.value()).isEqualTo("{sku: A-1");
                    assertThat(refused.complaint()).startsWith("is not JSON: ");
                });
    }

    @Test
    void aTextDateInTheDeclaredFormatBecomesTheCanonicalWireText() {
        assertThat(DeclaredKinds.read("d", field("date", "yyyy/MM/dd", null), "2026/01/15"))
                .isEqualTo("2026-01-15");
        assertThat(DeclaredKinds.read("d", field("date", "yyyyMMdd", null), "20240103"))
                .isEqualTo("2024-01-03");
        assertThat(DeclaredKinds.read("t", field("datetime", "yyyy/MM/dd HH:mm", null),
                "2026/01/15 22:30")).isEqualTo("2026-01-15T22:30:00");
        // The kind's default pattern when none is declared.
        assertThat(DeclaredKinds.read("d", field("date", null, null), "2026-01-15"))
                .isEqualTo("2026-01-15");
    }

    @Test
    void aValueAlreadyInTheCanonicalFormReadsUnchangedUnderAnotherFormat() {
        // A native date column declared for its domain's sake arrives canonical already.
        assertThat(DeclaredKinds.read("d", field("date", "yyyy/MM/dd", null), "2026-01-15"))
                .isEqualTo("2026-01-15");
        assertThat(DeclaredKinds.read("t", field("datetime", "yyyy/MM/dd HH:mm", null),
                "2026-01-15T22:30:00.123456")).isEqualTo("2026-01-15T22:30:00.123456");
    }

    @Test
    void aTextDateInNeitherFormIsRefused() {
        assertThatThrownBy(() -> DeclaredKinds.read("d", field("date", "yyyy/MM/dd", null),
                "15.01.2026"))
                .isInstanceOf(ColumnValueException.class)
                .satisfies(ex -> assertThat(((ColumnValueException) ex).complaint())
                        .isEqualTo("is not a valid date (yyyy/MM/dd)"));
    }

    /** Decision 23: the entry's locale, else the root locale — never the platform's. */
    @Test
    void aTextNumberParsesInTheEntrysLocale() {
        InputField german = withLocale(field("number", "#,##0.00", null), "de-DE");

        assertThat(DeclaredKinds.read("n", german, "1.234,50"))
                .isEqualTo(new BigDecimal("1234.50"));
        assertThatThrownBy(() -> DeclaredKinds.read("n", german, "1,234.50"))
                .isInstanceOf(ColumnValueException.class);
        assertThat(DeclaredKinds.read("d", withLocale(field("date", "d. MMMM yyyy", null), "de-DE"),
                "15. Januar 2026")).isEqualTo("2026-01-15");
    }

    @Test
    void aTextNumberInTheDeclaredPatternBecomesADecimalAndANumberStaysOne() {
        assertThat(DeclaredKinds.read("n", field("number", "#,##0.00", null), "1,234.50"))
                .isEqualTo(new BigDecimal("1234.50"));
        assertThat(DeclaredKinds.read("n", field("number", null, null), "12.5"))
                .isEqualTo(new BigDecimal("12.5"));
        assertThat(DeclaredKinds.read("n", field("number", "#,##0.00", null), 7L))
                .isEqualTo(7L);
        assertThatThrownBy(() -> DeclaredKinds.read("n", field("number", "#,##0.00", null),
                "1.234,50"))
                .isInstanceOf(ColumnValueException.class);
    }

    @Test
    void theReadFailureNamesSourceColumnRowAndKind() {
        ColumnValueException cause = new ColumnValueException("payload", "{oops",
                "is not JSON: Unexpected character");

        TqlException failure = DeclaredKinds.unparseable("main", "payload", 3, "json", cause);

        assertThat(failure.code().toString()).isEqualTo("TQL-SQL-2503");
        assertThat(failure.getMessage()).isEqualTo("TQL-SQL-2503: Source 'main' column"
                + " 'payload' row index 3: the value '{oops' is not JSON: Unexpected character"
                + " - declared json");
        assertThat(failure.details()).containsEntry("column", "payload")
                .containsEntry("row", 3).containsEntry("kind", "json");
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private static InputField field(String type, String format, String domain) {
        return new InputField(type, false, null, null, null, null, null, null, null, null,
                format, null, null, null, null, null, domain, null, null, null, null);
    }

    /** The same field with a locale — the 23-key shape spelled out once. */
    private static InputField withLocale(InputField f, String locale) {
        return new InputField(f.type(), f.required(), f.defaultValue(), f.min(), f.max(),
                f.maxLength(), f.enumValues(), f.writable(), f.classification(), f.mask(),
                f.format(), f.items(), f.pattern(), f.minLength(), f.requiredWhen(),
                f.columns(), f.domain(), f.widget(), f.codes(), f.policy(), f.description(),
                f.lookup(), locale);
    }

    private static Binding source(Map<String, InputField> result) {
        return new Binding("q.sql", null, "query", null, null, null, null, null, null, null,
                null, null, null, null, null, null, result);
    }

    private static RouteDefinition route(Map<String, InputField> input,
            Map<String, Binding> bindings) {
        return route("query-json", input, bindings);
    }

    private static RouteDefinition route(String recipe, Map<String, InputField> input,
            Map<String, Binding> bindings) {
        Map<String, Binding> sources = new java.util.LinkedHashMap<>();
        Map<String, Binding> steps = new java.util.LinkedHashMap<>();
        bindings.forEach((name, binding) -> ("update".equals(binding.mode()) ? steps : sources)
                .put(name, binding));
        return new RouteDefinition("tesseraql/v1", "r", "route", recipe, input, null,
                null, null, null, null, steps, sources, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }
}
