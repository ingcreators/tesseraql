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
            return mapper.readValue(Files.readString(file), TestSuite.class);
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
            return mapper.readValue(yaml, TestSuite.class);
        } catch (IOException ex) {
            throw new TqlException(PARSE_ERROR, "Failed to parse test suite: " + ex.getMessage());
        }
    }
}
