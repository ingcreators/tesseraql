package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateNotifierTest {

    private static final Duration DAY = Duration.ofHours(24);
    private static final Instant NOW = Instant.parse("2026-06-20T00:00:00Z");

    @Test
    void parseTagNameStripsLeadingV() {
        assertThat(UpdateNotifier.parseTagName("{\"tag_name\":\"v0.3.1\",\"name\":\"x\"}"))
                .contains("0.3.1");
        assertThat(UpdateNotifier.parseTagName("{ \"tag_name\" : \"0.4.0\" }")).contains("0.4.0");
        assertThat(UpdateNotifier.parseTagName("{\"no\":\"tag\"}")).isEmpty();
        assertThat(UpdateNotifier.parseTagName(null)).isEmpty();
    }

    @Test
    void isNewerComparesNumericallyAndIgnoresPreRelease() {
        assertThat(UpdateNotifier.isNewer("0.3.1", "0.3.0")).isTrue();
        assertThat(UpdateNotifier.isNewer("1.0.0", "0.9.9")).isTrue();
        assertThat(UpdateNotifier.isNewer("0.3.0", "0.3.1")).isFalse();
        assertThat(UpdateNotifier.isNewer("0.3.0", "0.3.0")).isFalse();
        // A dev build ahead of the latest release is not nudged: 0.4.0-SNAPSHOT -> 0.4.0 > 0.3.1.
        assertThat(UpdateNotifier.isNewer("0.3.1", "0.4.0-SNAPSHOT")).isFalse();
        assertThat(UpdateNotifier.isNewer("garbage", "0.3.0")).isFalse();
    }

    @Test
    void optedOutByEnvOrCi() {
        assertThat(UpdateNotifier.isOptedOut(Map.of())).isFalse();
        assertThat(UpdateNotifier.isOptedOut(Map.of(UpdateNotifier.OPT_OUT_ENV, "1"))).isTrue();
        assertThat(UpdateNotifier.isOptedOut(Map.of("CI", "true"))).isTrue();
        // Falsey values do not opt out.
        assertThat(UpdateNotifier.isOptedOut(Map.of(UpdateNotifier.OPT_OUT_ENV, "false")))
                .isFalse();
        assertThat(UpdateNotifier.isOptedOut(Map.of("CI", "0"))).isFalse();
    }

    @Test
    void defaultCacheFileHonorsTesseraqlHome(@TempDir Path home) {
        Path cache = UpdateNotifier
                .defaultCacheFile(Map.of(UpdateNotifier.HOME_ENV, home.toString()));
        assertThat(cache).isEqualTo(home.resolve("update-check.properties"));
    }

    @Test
    void refreshWritesCacheAndNotifyPrintsWhenNewer(@TempDir Path dir) {
        Path cache = dir.resolve("update-check.properties");
        UpdateNotifier notifier = new UpdateNotifier(
                "0.3.0", cache, () -> NOW, () -> Optional.of("0.3.1"), DAY);

        assertThat(notifier.dueForRefresh()).isTrue(); // no cache yet
        notifier.refreshNow();
        assertThat(cache).exists();
        assertThat(notifier.dueForRefresh()).isFalse(); // freshly checked

        assertThat(capture(notifier))
                .contains("A newer TesseraQL is available: 0.3.1")
                .contains("current 0.3.0")
                .contains("releases/latest");
    }

    @Test
    void noNoticeWhenCacheMissingOrNotNewer(@TempDir Path dir) {
        Path cache = dir.resolve("update-check.properties");
        // No cache file at all -> nothing printed.
        UpdateNotifier fresh = new UpdateNotifier(
                "0.3.0", cache, () -> NOW, Optional::empty, DAY);
        assertThat(capture(fresh)).isEmpty();

        // Latest equals current -> nothing printed.
        UpdateNotifier upToDate = new UpdateNotifier(
                "0.3.1", cache, () -> NOW, () -> Optional.of("0.3.1"), DAY);
        upToDate.refreshNow();
        assertThat(capture(upToDate)).isEmpty();
    }

    @Test
    void failedFetchStillRecordsCheckTimeWithoutVersion(@TempDir Path dir) {
        Path cache = dir.resolve("update-check.properties");
        UpdateNotifier notifier = new UpdateNotifier(
                "0.3.0", cache, () -> NOW, Optional::empty, DAY);
        notifier.refreshNow();

        assertThat(cache).exists();
        assertThat(notifier.dueForRefresh()).isFalse(); // checked, so we back off for the interval
        assertThat(capture(notifier)).isEmpty(); // but no version means no nudge
    }

    @Test
    void staleCacheIsDueForRefresh(@TempDir Path dir) {
        Path cache = dir.resolve("update-check.properties");
        Instant past = NOW.minus(Duration.ofHours(25));
        UpdateNotifier writer = new UpdateNotifier(
                "0.3.0", cache, () -> past, () -> Optional.of("0.3.1"), DAY);
        writer.refreshNow();

        UpdateNotifier now = new UpdateNotifier(
                "0.3.0", cache, () -> NOW, () -> Optional.of("0.3.1"), DAY);
        assertThat(now.dueForRefresh()).isTrue();
    }

    private static String capture(UpdateNotifier notifier) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        notifier.notifyFromCache(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
