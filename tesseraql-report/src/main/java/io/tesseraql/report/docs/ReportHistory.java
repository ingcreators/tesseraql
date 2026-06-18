package io.tesseraql.report.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Maintains the run history that feeds the portal's coverage trend (documentation portal v2). Each
 * run appends a compact {@link Entry} to {@code history.json}; by default the file is kept as a ring
 * of the most recent {@link #DEFAULT_MAX_ENTRIES} runs so the app home never grows unbounded, but a
 * non-positive cap retains the full history for longer-term trends (Studio backlog F9). Like
 * {@code report.json}, it is an optional sidecar that is never packed into the {@code .tqlapp}.
 */
public final class ReportHistory {

    /** The default number of runs retained in {@code history.json} (a non-positive cap keeps all). */
    public static final int DEFAULT_MAX_ENTRIES = 20;

    private static final TqlErrorCode HISTORY_ERROR = new TqlErrorCode(TqlDomain.REPORT, 2006);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReportHistory() {
    }

    /**
     * One run's trend point: the run identity and the aggregate numbers the dashboard plots.
     *
     * @param runId          the run identity
     * @param generatedAt    ISO-8601 instant of the run
     * @param total          number of test cases
     * @param passed         cases that passed
     * @param failed         cases that failed
     * @param sqlLineRatio   aggregate SQL line coverage in {@code [0,1]}
     * @param sqlBranchRatio aggregate SQL branch coverage in {@code [0,1]}
     * @param gatePassed     whether the coverage gate passed
     */
    public record Entry(String runId, String generatedAt, int total, long passed, long failed,
            double sqlLineRatio, double sqlBranchRatio, boolean gatePassed) {

        /** Projects a report's run-level facts to a history trend point. */
        public static Entry from(ReportDoc report) {
            ReportDoc.Summary summary = report.summary();
            return new Entry(report.runId(), report.generatedAt(), summary.total(),
                    summary.passed(), summary.failed(), summary.sqlLineRatio(),
                    summary.sqlBranchRatio(), summary.gatePassed());
        }
    }

    /**
     * Appends {@code entry} to the history at {@code historyFile}, trimming to the most recent
     * {@code maxEntries}, and writes the file back. Returns the retained entries, oldest first. A
     * non-positive {@code maxEntries} keeps the full history (an unbounded trend, Studio backlog F9).
     */
    public static List<Entry> append(Path historyFile, Entry entry, int maxEntries) {
        List<Entry> entries = read(historyFile);
        entries.add(entry);
        if (maxEntries > 0 && entries.size() > maxEntries) {
            entries = new ArrayList<>(entries.subList(entries.size() - maxEntries, entries.size()));
        }
        write(historyFile, entries);
        return entries;
    }

    /** Reads the history, or an empty list when the file is absent or unreadable. */
    public static List<Entry> read(Path historyFile) {
        if (!Files.isRegularFile(historyFile)) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(Arrays.asList(MAPPER.readValue(historyFile.toFile(),
                    Entry[].class)));
        } catch (IOException ex) {
            // A corrupt or incompatible history must not fail the build; start a fresh ring.
            return new ArrayList<>();
        }
    }

    private static void write(Path historyFile, List<Entry> entries) {
        try {
            if (historyFile.getParent() != null) {
                Files.createDirectories(historyFile.getParent());
            }
            Files.writeString(historyFile,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(entries));
        } catch (IOException ex) {
            throw new TqlException(HISTORY_ERROR,
                    "Failed to write " + historyFile + ": " + ex.getMessage());
        }
    }
}
