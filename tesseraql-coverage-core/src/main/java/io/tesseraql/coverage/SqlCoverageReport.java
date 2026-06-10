package io.tesseraql.coverage;

import java.util.Set;

/**
 * SQL coverage for a single SQL file, aggregated across renders (design ch. 14).
 *
 * @param sqlId             the SQL file id
 * @param coveredLines      source lines that were emitted at least once
 * @param coverableLineCount number of source lines the template could emit (the line denominator)
 * @param branchCount       number of distinct conditional branches seen
 * @param branchOutcomes    number of branch outcomes observed (max {@code 2 * branchCount})
 * @param branchRatio       {@code branchOutcomes / (2 * branchCount)}; 1.0 when there are no branches
 * @param lineRatio         {@code coveredLines / coverableLineCount}; 1.0 when the denominator is unknown
 */
public record SqlCoverageReport(
        String sqlId,
        Set<Integer> coveredLines,
        int coverableLineCount,
        int branchCount,
        int branchOutcomes,
        double branchRatio,
        double lineRatio) {

    public int lineCount() {
        return coveredLines.size();
    }
}
