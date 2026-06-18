package io.tesseraql.report.docs;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.report.docs.ReportHistory.Entry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportHistoryTest {

    private static Entry entry(String runId) {
        return new Entry(runId, "2026-06-15T00:00:00Z", 10, 9, 1, 0.8, 0.75, true);
    }

    @Test
    void readsAnEmptyListWhenTheFileIsAbsent(@TempDir Path dir) {
        assertThat(ReportHistory.read(dir.resolve("history.json"))).isEmpty();
    }

    @Test
    void appendsAndPersistsAcrossReads(@TempDir Path dir) {
        Path file = dir.resolve("history.json");

        ReportHistory.append(file, entry("r1"), 20);
        List<Entry> after = ReportHistory.append(file, entry("r2"), 20);

        assertThat(after).extracting(Entry::runId).containsExactly("r1", "r2");
        assertThat(ReportHistory.read(file)).extracting(Entry::runId).containsExactly("r1", "r2");
    }

    @Test
    void keepsOnlyTheMostRecentEntriesAsARing(@TempDir Path dir) {
        Path file = dir.resolve("history.json");

        for (int i = 1; i <= 5; i++) {
            ReportHistory.append(file, entry("r" + i), 3);
        }

        assertThat(ReportHistory.read(file)).extracting(Entry::runId)
                .containsExactly("r3", "r4", "r5");
    }

    @Test
    void keepsTheFullHistoryWhenTheCapIsNonPositive(@TempDir Path dir) {
        Path file = dir.resolve("history.json");

        // A non-positive cap retains every run (longer-term trends, backlog F9).
        for (int i = 1; i <= 25; i++) {
            ReportHistory.append(file, entry("r" + i), 0);
        }
        assertThat(ReportHistory.read(file)).hasSize(25);

        ReportHistory.append(file, entry("r26"), -1);
        assertThat(ReportHistory.read(file)).hasSize(26)
                .last().extracting(Entry::runId).isEqualTo("r26");
    }

    @Test
    void recoversFromACorruptHistoryByStartingFresh(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("history.json");
        Files.writeString(file, "{ not json");

        assertThat(ReportHistory.read(file)).isEmpty();
        List<Entry> after = ReportHistory.append(file, entry("r1"), 20);
        assertThat(after).extracting(Entry::runId).containsExactly("r1");
    }

    @Test
    void derivesAnEntryFromAReport() {
        ReportDoc report = new ReportDoc(ReportDoc.SCHEMA_VERSION, "build-7",
                "2026-06-15T12:00:00Z",
                new ReportDoc.Summary(12, 11, 1, 0.9, 0.85, false),
                new ReportDoc.Thresholds(0.8, 0.7, java.util.Map.of()),
                new ReportDoc.Gate(false, List.of("x: line coverage")),
                List.of(), java.util.Map.of());

        Entry entry = Entry.from(report);

        assertThat(entry.runId()).isEqualTo("build-7");
        assertThat(entry.total()).isEqualTo(12);
        assertThat(entry.passed()).isEqualTo(11);
        assertThat(entry.failed()).isEqualTo(1);
        assertThat(entry.sqlLineRatio()).isEqualTo(0.9);
        assertThat(entry.gatePassed()).isFalse();
    }
}
