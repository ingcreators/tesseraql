package io.tesseraql.report;

import io.tesseraql.test.TestReport;
import io.tesseraql.test.TestReport.TestResult;

/**
 * Renders a {@link TestReport} as JUnit XML for CI consumption (design ch. 15).
 */
public final class JUnitXmlReporter {

    private JUnitXmlReporter() {
    }

    public static String toXml(TestReport report, String suiteName) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<testsuite name=\"").append(escape(suiteName))
                .append("\" tests=\"").append(report.results().size())
                .append("\" failures=\"").append(report.failed())
                .append("\">\n");
        for (TestResult result : report.results()) {
            xml.append("  <testcase name=\"").append(escape(result.name())).append("\"");
            if (result.passed()) {
                xml.append("/>\n");
            } else {
                xml.append(">\n    <failure message=\"").append(escape(result.message()))
                        .append("\"/>\n  </testcase>\n");
            }
        }
        xml.append("</testsuite>\n");
        return xml.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
