package io.tesseraql.report;

import io.tesseraql.coverage.SqlCoverageReport;
import java.util.Map;
import java.util.TreeSet;

/**
 * Renders SQL coverage as SonarQube generic coverage XML (design ch. 15), imported with the
 * {@code sonar.coverageReportPaths} analysis property. Each SQL file lists its coverable lines
 * with a covered flag.
 */
public final class SonarQubeReporter {

    private SonarQubeReporter() {
    }

    /** Serializes per-file SQL coverage reports keyed by SQL id into a generic coverage document. */
    public static String toXml(Map<String, SqlCoverageReport> reports) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<coverage version=\"1\">\n");
        for (Map.Entry<String, SqlCoverageReport> entry : reports.entrySet()) {
            SqlCoverageReport report = entry.getValue();
            xml.append("  <file path=\"").append(escape(entry.getKey())).append("\">\n");
            for (int line : new TreeSet<>(report.coverableLines())) {
                xml.append("    <lineToCover lineNumber=\"").append(line).append("\" covered=\"")
                        .append(report.coveredLines().contains(line)).append("\"/>\n");
            }
            xml.append("  </file>\n");
        }
        xml.append("</coverage>\n");
        return xml.toString();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
