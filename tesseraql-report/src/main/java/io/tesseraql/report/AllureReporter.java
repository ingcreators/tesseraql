package io.tesseraql.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.test.TestReport;
import io.tesseraql.test.TestReport.TestResult;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Renders a {@link TestReport} as Allure 2 result files (design ch. 15): one
 * {@code <uuid>-result.json} per test case, written into an {@code allure-results} directory the
 * Allure CLI turns into a report. UUIDs are derived from the suite and case names so repeated runs
 * produce identical files (reproducibility, design ch. 48); timing is intentionally omitted for
 * the same reason.
 */
public final class AllureReporter {

    private static final TqlErrorCode REPORT_ERROR = new TqlErrorCode(TqlDomain.REPORT, 1003);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AllureReporter() {
    }

    /** Builds the Allure result files: file name to JSON content, one entry per test case. */
    public static Map<String, String> toResults(TestReport report, String suiteName) {
        Map<String, String> files = new LinkedHashMap<>();
        for (TestResult result : report.results()) {
            String uuid = UUID.nameUUIDFromBytes(
                    (suiteName + "/" + result.name()).getBytes(StandardCharsets.UTF_8)).toString();

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("uuid", uuid);
            entry.put("historyId", uuid);
            entry.put("name", result.name());
            entry.put("fullName", suiteName + "." + result.name());
            entry.put("status", result.passed() ? "passed" : "failed");
            if (!result.passed() && result.message() != null) {
                entry.put("statusDetails", Map.of("message", result.message()));
            }
            entry.put("stage", "finished");
            entry.put("labels", List.of(Map.of("name", "suite", "value", suiteName)));

            files.put(uuid + "-result.json", write(entry));
        }
        return files;
    }

    private static String write(Map<String, Object> entry) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(entry);
        } catch (JsonProcessingException ex) {
            throw new TqlException(REPORT_ERROR,
                    "Failed to render Allure result: " + ex.getMessage());
        }
    }
}
