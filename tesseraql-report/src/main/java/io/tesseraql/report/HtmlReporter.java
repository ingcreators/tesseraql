package io.tesseraql.report;

import io.tesseraql.test.TestReport;
import io.tesseraql.test.TestReport.TestResult;

/**
 * Renders a {@link TestReport} as a self-contained HTML page (design ch. 15).
 */
public final class HtmlReporter {

    private HtmlReporter() {
    }

    public static String toHtml(TestReport report, String title) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html>\n<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n<title>").append(escape(title))
                .append("</title>\n")
                .append("</head>\n<body>\n");
        html.append("<h1>").append(escape(title)).append("</h1>\n");
        html.append("<p>Total: ").append(report.results().size())
                .append(", Passed: ").append(report.passed())
                .append(", Failed: ").append(report.failed()).append("</p>\n");
        html.append(
                "<table>\n<thead><tr><th>Test</th><th>Result</th><th>Message</th></tr></thead>\n")
                .append("<tbody>\n");
        for (TestResult result : report.results()) {
            html.append("<tr><td>").append(escape(result.name()))
                    .append("</td><td>").append(result.passed() ? "PASS" : "FAIL")
                    .append("</td><td>").append(escape(result.message()))
                    .append("</td></tr>\n");
        }
        html.append("</tbody>\n</table>\n</body>\n</html>\n");
        return html.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
