package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The {@code serve --watch} debounce and noise filter in isolation: a burst of editor save
 * events coalesces into one deduplicated batch after a quiet period, and backup/swap/temp
 * files never trigger a reload at all.
 */
class RouteWatcherTest {

    @Test
    void aBurstCoalescesIntoOneDeduplicatedBatchAfterTheQuietPeriod() {
        AtomicLong now = new AtomicLong(1000);
        RouteWatcher.Debounce debounce = new RouteWatcher.Debounce(300, now::get);
        assertThat(debounce.hasPending()).isFalse();
        assertThat(debounce.drainIfQuiet()).isEmpty();

        // An editor save fires several events for the same file, plus a sibling.
        debounce.offer("web/api/ping/ping.sql");
        debounce.offer("web/api/ping/ping.sql");
        now.addAndGet(100);
        debounce.offer("web/api/ping/get.yml");

        // Not quiet yet: nothing drains, and the batch is still pending.
        assertThat(debounce.hasPending()).isTrue();
        now.addAndGet(299);
        assertThat(debounce.drainIfQuiet()).isEmpty();

        // Quiet elapsed: one batch, deduplicated, in first-seen order — then empty again.
        now.addAndGet(1);
        assertThat(debounce.drainIfQuiet())
                .containsExactly("web/api/ping/ping.sql", "web/api/ping/get.yml");
        assertThat(debounce.hasPending()).isFalse();
        assertThat(debounce.drainIfQuiet()).isEmpty();
    }

    @Test
    void everyOfferRestartsTheQuietPeriod() {
        AtomicLong now = new AtomicLong(0);
        RouteWatcher.Debounce debounce = new RouteWatcher.Debounce(300, now::get);
        debounce.offer("web/a.sql");
        now.addAndGet(250);
        debounce.offer("web/b.sql");

        // 300ms after the FIRST offer the batch must still be held back.
        now.addAndGet(50);
        assertThat(debounce.drainIfQuiet()).isEmpty();
        assertThat(debounce.millisUntilQuiet()).isEqualTo(250);

        now.addAndGet(250);
        assertThat(debounce.drainIfQuiet()).containsExactly("web/a.sql", "web/b.sql");
        assertThat(debounce.millisUntilQuiet()).isZero();
    }

    @Test
    void editorNoiseIsFilteredAndRouteSourcesAreNot() {
        // Backup, swap, temp, and hidden files — the shapes editors litter next to a save.
        assertThat(RouteWatcher.isNoise("ping.sql~")).isTrue();
        assertThat(RouteWatcher.isNoise(".ping.sql.swp")).isTrue();
        assertThat(RouteWatcher.isNoise("get.yml.swp")).isTrue();
        assertThat(RouteWatcher.isNoise("get.yml.swx")).isTrue();
        assertThat(RouteWatcher.isNoise("ping.sql.tmp")).isTrue();
        assertThat(RouteWatcher.isNoise(".goutputstream-abc123")).isTrue();
        assertThat(RouteWatcher.isNoise(".git")).isTrue();

        // The sources a save is actually about.
        assertThat(RouteWatcher.isNoise("ping.sql")).isFalse();
        assertThat(RouteWatcher.isNoise("get.yml")).isFalse();
        assertThat(RouteWatcher.isNoise("detail.html")).isFalse();
    }
}
