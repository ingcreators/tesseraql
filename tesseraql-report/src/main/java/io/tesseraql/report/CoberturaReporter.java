package io.tesseraql.report;

import io.tesseraql.coverage.SqlCoverageReport;
import java.util.Map;
import java.util.TreeSet;

/**
 * Renders SQL coverage as Cobertura XML (design ch. 15), the line/branch coverage format consumed
 * by CI coverage publishers. Each SQL file maps to one Cobertura class whose lines are the file's
 * coverable lines; the timestamp is fixed at 0 so the report stays reproducible.
 */
public final class CoberturaReporter {

    private CoberturaReporter() {
    }

    /** Serializes per-file SQL coverage reports keyed by SQL id into a Cobertura document. */
    public static String toXml(Map<String, SqlCoverageReport> reports) {
        long linesValid = 0;
        long linesCovered = 0;
        long branchesValid = 0;
        long branchesCovered = 0;
        for (SqlCoverageReport report : reports.values()) {
            linesValid += report.coverableLineCount();
            linesCovered += report.coverableLines().stream().filter(report.coveredLines()::contains)
                    .count();
            branchesValid += 2L * report.branchCount();
            branchesCovered += report.branchOutcomes();
        }

        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append(String.format(java.util.Locale.ROOT,
                "<coverage line-rate=\"%s\" branch-rate=\"%s\" lines-valid=\"%d\""
                        + " lines-covered=\"%d\" branches-valid=\"%d\" branches-covered=\"%d\""
                        + " complexity=\"0\" version=\"tesseraql\" timestamp=\"0\">\n",
                rate(linesCovered, linesValid), rate(branchesCovered, branchesValid),
                linesValid, linesCovered, branchesValid, branchesCovered));
        xml.append("  <sources>\n    <source>.</source>\n  </sources>\n");
        xml.append("  <packages>\n    <package name=\"sql\" line-rate=\"")
                .append(rate(linesCovered, linesValid)).append("\" branch-rate=\"")
                .append(rate(branchesCovered, branchesValid)).append("\" complexity=\"0\">\n")
                .append("      <classes>\n");
        for (Map.Entry<String, SqlCoverageReport> entry : reports.entrySet()) {
            appendClass(xml, entry.getKey(), entry.getValue());
        }
        xml.append("      </classes>\n    </package>\n  </packages>\n</coverage>\n");
        return xml.toString();
    }

    private static void appendClass(StringBuilder xml, String sqlId, SqlCoverageReport report) {
        String name = sqlId.replace('/', '.');
        xml.append("        <class name=\"").append(escape(name)).append("\" filename=\"")
                .append(escape(sqlId)).append("\" line-rate=\"").append(format(report.lineRatio()))
                .append("\" branch-rate=\"").append(format(report.branchRatio()))
                .append("\" complexity=\"0\">\n")
                .append("          <methods/>\n          <lines>\n");
        for (int line : new TreeSet<>(report.coverableLines())) {
            xml.append("            <line number=\"").append(line).append("\" hits=\"")
                    .append(report.coveredLines().contains(line) ? 1 : 0)
                    .append("\" branch=\"false\"/>\n");
        }
        xml.append("          </lines>\n        </class>\n");
    }

    private static String rate(long covered, long valid) {
        return valid == 0 ? format(1.0) : format((double) covered / valid);
    }

    /** Locale-independent decimal so the report is byte-stable across environments. */
    private static String format(double ratio) {
        return String.format(java.util.Locale.ROOT, "%.4f", ratio);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
