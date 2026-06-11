package io.tesseraql.core.threading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ExecutionLanesTest {

    @Test
    void virtualLaneRunsTasksOnVirtualThreads() throws Exception {
        try (ExecutionLanes lanes = ExecutionLanes.of(List.of(LanePolicy.virtual("io", 4)))) {
            Future<Boolean> isVirtual = lanes.lane("io").executor()
                    .submit(() -> Thread.currentThread().isVirtual());
            assertThat(isVirtual.get(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void platformLaneRunsTasksOnPlatformThreads() throws Exception {
        try (ExecutionLanes lanes = ExecutionLanes.of(List.of(LanePolicy.platform("cpu", 2)))) {
            Future<Boolean> isVirtual = lanes.lane("cpu").executor()
                    .submit(() -> Thread.currentThread().isVirtual());
            assertThat(isVirtual.get(5, TimeUnit.SECONDS)).isFalse();
        }
    }

    @Test
    void admissionBoundsConcurrencyAndAppliesBackpressure() throws Exception {
        try (ExecutionLanes lanes = ExecutionLanes.of(List.of(LanePolicy.virtual("io", 1)))) {
            Lane lane = lanes.lane("io");
            CountDownLatch hold = new CountDownLatch(1);
            CountDownLatch started = new CountDownLatch(1);
            AtomicBoolean ran = new AtomicBoolean();

            assertThat(lane.tryAdmit()).isTrue();
            Future<?> task = lane.executor().submit(() -> {
                started.countDown();
                try {
                    hold.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                ran.set(true);
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            // The single permit is taken, so the lane is now at capacity.
            assertThat(lane.tryAdmit()).isFalse();
            assertThat(lane.available()).isZero();

            hold.countDown();
            task.get(5, TimeUnit.SECONDS);
            lane.release();
            assertThat(lane.tryAdmit()).isTrue();
            lane.release();

            assertThat(lane.admittedCount()).isEqualTo(2);
            assertThat(lane.rejectedCount()).isEqualTo(1);
            assertThat(ran.get()).isTrue();
        }
    }

    @Test
    void unknownLaneThrows() {
        try (ExecutionLanes lanes = ExecutionLanes.empty()) {
            assertThat(lanes.has("io")).isFalse();
            assertThatThrownBy(() -> lanes.lane("io"))
                    .isInstanceOf(TqlException.class)
                    .hasMessageContaining("io");
        }
    }

    @Test
    void duplicateLaneNamesAreRejected() {
        assertThatThrownBy(() -> ExecutionLanes.of(
                List.of(LanePolicy.virtual("io", 2), LanePolicy.platform("io", 2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("io");
    }

    @Test
    void rejectedTasksSurfaceAfterShutdown() {
        ExecutionLanes lanes = ExecutionLanes.of(List.of(LanePolicy.virtual("io", 2)));
        Lane lane = lanes.lane("io");
        lanes.close();
        assertThatThrownBy(() -> {
            Future<?> future = lane.executor().submit(() -> "x");
            future.get(1, TimeUnit.SECONDS);
        }).isInstanceOfAny(java.util.concurrent.RejectedExecutionException.class, ExecutionException.class);
    }
}
