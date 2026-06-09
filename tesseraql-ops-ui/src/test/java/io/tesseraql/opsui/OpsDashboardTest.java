package io.tesseraql.opsui;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.threading.ExecutionLanes;
import io.tesseraql.core.threading.LanePolicy;
import io.tesseraql.opsui.OpsDashboard.LaneStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpsDashboardTest {

    @Test
    void reportsAllLanesWithTheirTypes() {
        try (ExecutionLanes lanes = ExecutionLanes.of(List.of(
                LanePolicy.virtual("io", 4), LanePolicy.platform("cpu", 2)))) {
            assertThat(OpsDashboard.laneStatuses(lanes))
                    .extracting(LaneStatus::name, LaneStatus::type)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple("io", "VIRTUAL"),
                            org.assertj.core.groups.Tuple.tuple("cpu", "PLATFORM"));
        }
    }

    @Test
    void laneStatusReflectsCapacityAndUsage() {
        try (ExecutionLanes lanes = ExecutionLanes.of(List.of(LanePolicy.virtual("io", 3)))) {
            lanes.lane("io").tryAdmit();
            lanes.lane("io").tryAdmit();

            List<LaneStatus> statuses = OpsDashboard.laneStatuses(lanes);
            assertThat(statuses).singleElement().satisfies(status -> {
                assertThat(status.name()).isEqualTo("io");
                assertThat(status.type()).isEqualTo("VIRTUAL");
                assertThat(status.maxConcurrency()).isEqualTo(3);
                assertThat(status.inUse()).isEqualTo(2);
                assertThat(status.available()).isEqualTo(1);
                assertThat(status.admitted()).isEqualTo(2);
                assertThat(status.rejected()).isZero();
            });
        }
    }

    @Test
    void rejectionsAreCounted() {
        try (ExecutionLanes lanes = ExecutionLanes.of(List.of(LanePolicy.virtual("io", 1)))) {
            assertThat(lanes.lane("io").tryAdmit()).isTrue();
            assertThat(lanes.lane("io").tryAdmit()).isFalse();

            assertThat(OpsDashboard.laneStatuses(lanes)).singleElement().satisfies(status -> {
                assertThat(status.inUse()).isEqualTo(1);
                assertThat(status.rejected()).isEqualTo(1);
            });
        }
    }
}
