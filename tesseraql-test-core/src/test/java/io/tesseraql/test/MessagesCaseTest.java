package io.tesseraql.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.coverage.ItemCoverage;
import io.tesseraql.test.TestSuite.Expectation;
import io.tesseraql.test.TestSuite.MessagesTarget;
import io.tesseraql.test.TestSuite.TestCase;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessagesCaseTest {

    @TempDir
    Path appHome;

    @BeforeEach
    void catalogs() throws Exception {
        Path messages = Files.createDirectories(appHome.resolve("messages"));
        Files.writeString(messages.resolve("en.yml"), """
                users:
                  provision:
                    unknown-user: The user does not exist.
                """);
        Files.writeString(messages.resolve("ja.yml"), """
                users.provision.unknown-user: 指定されたユーザーは存在しません。
                """);
    }

    @Test
    void aMessagesCaseAssertsOnResolvedTexts() {
        TestCase test = new TestCase("japanese provisioning text", null, null, Map.of(),
                new Expectation(1, List.of(Map.of(
                        "key", "users.provision.unknown-user",
                        "locale", "ja",
                        "text", "指定されたユーザーは存在しません。"))),
                null, null,
                new MessagesTarget("ja", List.of("users.provision.unknown-user")));

        TestReport report = new TestRunner(null, appHome).run(new TestSuite(List.of(test)));

        assertThat(report.results()).singleElement()
                .satisfies(result -> assertThat(result.passed())
                        .withFailMessage(result.message()).isTrue());
    }

    @Test
    void aRegionalLocaleReadsTheBareLanguageCatalog() {
        TestCase test = new TestCase("ja-JP falls back to ja", null, null, Map.of(),
                new Expectation(null, List.of(Map.of(
                        "text", "指定されたユーザーは存在しません。"))),
                null, null,
                new MessagesTarget("ja-JP", List.of("users.provision.unknown-user")));

        TestReport report = new TestRunner(null, appHome).run(new TestSuite(List.of(test)));

        assertThat(report.results().get(0).passed())
                .withFailMessage(report.results().get(0).message()).isTrue();
    }

    @Test
    void aMissingKeyFailsItsExpectation() {
        TestCase test = new TestCase("missing key", null, null, Map.of(),
                new Expectation(null, List.of(Map.of("text", "anything"))),
                null, null, new MessagesTarget("ja", List.of("absent.key")));

        TestReport report = new TestRunner(null, appHome).run(new TestSuite(List.of(test)));

        assertThat(report.results().get(0).passed()).isFalse();
    }

    @Test
    void withoutKeysEveryVisibleEntryBecomesARow() {
        TestCase test = new TestCase("whole catalog", null, null, Map.of(),
                new Expectation(1, null), null, null, new MessagesTarget("en", null));

        TestReport report = new TestRunner(null, appHome).run(new TestSuite(List.of(test)));

        assertThat(report.results().get(0).passed())
                .withFailMessage(report.results().get(0).message()).isTrue();
    }

    @Test
    void messageCoverageDeclaresCatalogsAndCoversTestedLocales() {
        TestSuite suite = new TestSuite(List.of(
                new TestCase("ja texts", null, null, Map.of(), null, null, null,
                        new MessagesTarget("ja-JP", List.of("users.provision.unknown-user")))));

        ItemCoverage coverage = ManifestCoverage.message(
                new ManifestLoader().load(appHome), List.of(suite));

        assertThat(coverage.kind()).isEqualTo("message");
        assertThat(coverage.declared()).containsExactlyInAnyOrder("en", "ja");
        assertThat(coverage.covered()).containsExactly("ja");
    }
}
