package io.tesseraql.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.test.TestReport;
import io.tesseraql.test.TestReport.TestResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link TestReport} as JSON (design ch. 15).
 */
public final class JsonReporter {

    private static final TqlErrorCode REPORT_ERROR = new TqlErrorCode(TqlDomain.REPORT, 1001);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonReporter() {
    }

    public static String toJson(TestReport report) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("total", report.results().size());
        root.put("passed", report.passed());
        root.put("failed", report.failed());

        List<Map<String, Object>> cases = new ArrayList<>();
        for (TestResult result : report.results()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", result.name());
            entry.put("passed", result.passed());
            entry.put("message", result.message());
            cases.add(entry);
        }
        root.put("cases", cases);

        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            throw new TqlException(REPORT_ERROR, "Failed to render JSON report: " + ex.getMessage());
        }
    }
}
