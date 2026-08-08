package io.tesseraql.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads a declarative {@link TestSuite} from a YAML file with a {@code tests:} block (design ch. 13).
 */
public final class TestSuiteLoader {

    private static final TqlErrorCode PARSE_ERROR = new TqlErrorCode(TqlDomain.YAML, 1401);

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    public TestSuite load(Path file) {
        try {
            return requireVersion(mapper.readValue(Files.readString(file), TestSuite.class),
                    file.toString());
        } catch (IOException ex) {
            throw TqlException.builder(PARSE_ERROR)
                    .message("Failed to load test suite: " + ex.getMessage())
                    .source(file.toString())
                    .cause(ex)
                    .build();
        }
    }

    public TestSuite parse(String yaml) {
        try {
            return requireVersion(mapper.readValue(yaml, TestSuite.class), "test suite");
        } catch (IOException ex) {
            throw new TqlException(PARSE_ERROR, "Failed to parse test suite: " + ex.getMessage());
        }
    }

    /**
     * Every document family carries the version discriminator (docs/vocabulary-cleanup.md
     * slice 2); test suites were the one family without it.
     */
    private static TestSuite requireVersion(TestSuite suite, String source) {
        if (!"tesseraql/v1".equals(suite.version())) {
            throw new TqlException(PARSE_ERROR, source
                    + ": version must be 'tesseraql/v1' (the tests document is versioned like"
                    + " every other TesseraQL document)");
        }
        return suite;
    }
}
