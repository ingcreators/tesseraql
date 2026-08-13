package io.tesseraql.test;

import io.tesseraql.test.TestSuite.Expectation;
import java.util.List;
import java.util.Map;

/**
 * The one verdict every case kind is judged by: an {@code expect:} block against the outcome
 * of whatever produced the rows — a SQL file, a transition, a decision, a message catalog.
 */
final class Expectations {

    private Expectations() {
    }

    /** Checks an expectation against an outcome; null on pass, else the failure message. */
    static String assertOutcome(Expectation expect, SqlOutcome outcome) {
        if (expect == null) {
            return null;
        }
        if (outcome.updateCount() != null) {
            if (expect.rowCount() != null || !expect.rows().isEmpty()) {
                return "the target is a write affecting " + outcome.updateCount()
                        + " row(s); assert expect.updateCount, not rowCount/rows";
            }
            if (expect.updateCount() != null
                    && outcome.updateCount().intValue() != expect.updateCount()) {
                return "expected updateCount " + expect.updateCount() + " but was "
                        + outcome.updateCount();
            }
            return null;
        }
        List<Map<String, Object>> rows = outcome.rows();
        if (expect.updateCount() != null) {
            return "expected updateCount " + expect.updateCount()
                    + " but the target returned " + rows.size()
                    + " result row(s); assert rowCount/rows";
        }
        if (expect.rowCount() != null && rows.size() != expect.rowCount()) {
            return "expected rowCount " + expect.rowCount() + " but was " + rows.size();
        }
        for (int i = 0; i < expect.rows().size(); i++) {
            if (i >= rows.size()) {
                return "expected at least " + (i + 1) + " rows";
            }
            Map<String, Object> actual = rows.get(i);
            for (Map.Entry<String, Object> entry : expect.rows().get(i).entrySet()) {
                if (!looselyEqual(actual.get(entry.getKey()), entry.getValue())) {
                    return "row " + i + " field '" + entry.getKey()
                            + "' expected " + entry.getValue() + " but was "
                            + actual.get(entry.getKey());
                }
            }
        }
        return null;
    }

    private static boolean looselyEqual(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return actual == expected;
        }
        return String.valueOf(actual).equals(String.valueOf(expected));
    }
}
