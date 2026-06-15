package io.tesseraql.report.docs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.test.CrossReferenceIndex;
import io.tesseraql.test.TestSuite;
import io.tesseraql.test.TestSuite.TestCase;
import io.tesseraql.test.TestSuiteLoader;
import io.tesseraql.yaml.docs.RouteSpec;
import io.tesseraql.yaml.docs.RouteSpecGenerator;
import io.tesseraql.yaml.docs.RouteSpecModel;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.RouteFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Generates the full spec-layer documentation model and its {@code spec.json} artifact
 * (documentation portal v1, build-time half). It wraps the yaml-side {@link RouteSpecGenerator} for
 * the route/SQL/migration specs and joins each route to the declarative test cases that cover it,
 * loaded statically from {@code tests/} and linked through the shared {@link CrossReferenceIndex}.
 *
 * <p>The output is deterministic and byte-stable like {@code OpenApiGenerator}: routes keep the
 * generator's order, tests follow suite then declaration order, and serialization omits null
 * fields. It needs no running database — test specs are read, not executed (the report overlay is a
 * later layer). Errors are raised in the {@link TqlDomain#REPORT} domain.
 */
public final class AppDocGenerator {

    private static final TqlErrorCode GEN_ERROR = new TqlErrorCode(TqlDomain.REPORT, 2004);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RouteSpecGenerator routeSpecGenerator = new RouteSpecGenerator();
    private final TestSuiteLoader suiteLoader = new TestSuiteLoader();

    /** Builds the documentation model: route specs joined to their covering test cases. */
    public DocModel generate(AppManifest manifest) {
        List<TestSuite> suites = loadSuites(manifest.appHome());
        CrossReferenceIndex index = CrossReferenceIndex.of(manifest, suites);
        RouteSpecModel spec = routeSpecGenerator.generate(manifest);

        Map<String, RouteFile> byMethodPath = new LinkedHashMap<>();
        for (RouteFile route : manifest.routes()) {
            byMethodPath.put(key(route.httpMethod(), route.urlPath()), route);
        }

        List<DocModel.RouteDoc> routeDocs = new ArrayList<>();
        for (RouteSpec routeSpec : spec.routes()) {
            RouteFile route = byMethodPath.get(key(routeSpec.method(), routeSpec.path()));
            List<DocModel.TestCaseDoc> tests = new ArrayList<>();
            if (route != null) {
                for (TestCase test : index.casesFor(route)) {
                    tests.add(testCase(test));
                }
            }
            routeDocs.add(new DocModel.RouteDoc(routeSpec, tests));
        }
        return new DocModel(routeDocs, spec.migrations());
    }

    /** Serializes the documentation model as deterministic, byte-stable pretty JSON. */
    public String toJson(AppManifest manifest) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(generate(manifest));
        } catch (JsonProcessingException ex) {
            throw new TqlException(GEN_ERROR, "Failed to serialize spec.json: " + ex.getMessage());
        }
    }

    private static String key(String method, String path) {
        return method + " " + path;
    }

    private static DocModel.TestCaseDoc testCase(TestCase test) {
        if (test.sql() != null && test.sql().file() != null) {
            return new DocModel.TestCaseDoc(test.name(), "sql", test.sql().file());
        }
        if (test.contract() != null && !test.contract().isBlank()) {
            return new DocModel.TestCaseDoc(test.name(), "contract", test.contract());
        }
        if (test.validate() != null) {
            return new DocModel.TestCaseDoc(test.name(), "validation",
                    suffix(test.validate().route(), test.validate().rule()));
        }
        if (test.notifications() != null) {
            TestSuite.NotifyTarget target = test.notifications();
            String owner = target.route() != null ? target.route() : target.job();
            return new DocModel.TestCaseDoc(test.name(), "notification",
                    suffix(owner, target.id()));
        }
        if (test.httpCall() != null) {
            return new DocModel.TestCaseDoc(test.name(), "http-call",
                    suffix(test.httpCall().job(), test.httpCall().id()));
        }
        if (test.messages() != null) {
            return new DocModel.TestCaseDoc(test.name(), "messages", test.messages().locale());
        }
        return new DocModel.TestCaseDoc(test.name(), "unknown", null);
    }

    private static String suffix(String owner, String id) {
        if (owner == null) {
            return null;
        }
        return id == null ? owner : owner + "." + id;
    }

    /** Loads every {@code tests/**}{@code /*.yml} suite statically, sorted for determinism. */
    private List<TestSuite> loadSuites(Path appHome) {
        Path testsDir = appHome.resolve("tests");
        if (!Files.isDirectory(testsDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(testsDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .map(suiteLoader::load)
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
