package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The origin's roll-up over its members (docs/deployment-maturity.md decision 3): every member
 * down is down, any member not up is degraded but routable, a member with no roll-up yet is
 * unmeasured, and draining wins over all of it.
 */
class StackReadinessTest {

    @Test
    void everyMemberDownIsDownAndNotRoutable() {
        StackReadiness readiness = StackReadiness.of(false,
                Map.of("orders", "DOWN", "portal", "DOWN"));

        assertThat(readiness.status()).isEqualTo("DOWN");
        assertThat(readiness.routable()).isFalse();
        assertThat(readiness.json())
                .isEqualTo("{\"status\":\"DOWN\",\"down\":[\"orders\",\"portal\"]}");
    }

    @Test
    void oneMemberDownAmongOthersIsDegradedAndStillRoutable() {
        StackReadiness readiness = StackReadiness.of(false,
                Map.of("orders", "DOWN", "billing", "UP", "portal", "WARN"));

        assertThat(readiness.status()).isEqualTo("DEGRADED");
        assertThat(readiness.routable())
                .as("two replicas share every member's database: emptying the Service for one"
                        + " member's outage would take the healthy members down with it")
                .isTrue();
        assertThat(readiness.json())
                .isEqualTo("{\"status\":\"DEGRADED\",\"down\":[\"orders\"],\"warn\":[\"portal\"]}");
    }

    @Test
    void everyMemberUpIsUpWithNoLists() {
        assertThat(StackReadiness.of(false, Map.of("orders", "UP")).json())
                .isEqualTo("{\"status\":\"UP\"}");
        assertThat(StackReadiness.of(false, Map.of()).json())
                .as("a stack with nothing measured yet is routable")
                .isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    void aMemberWithNoRollUpYetIsUnmeasuredNotDegraded() {
        StackReadiness readiness = StackReadiness.of(false,
                Map.of("orders", "UNKNOWN", "billing", "DOWN"));

        assertThat(readiness.status())
                .as("the one measured member is down, and the unmeasured one does not vote")
                .isEqualTo("DOWN");
        assertThat(StackReadiness.of(false, Map.of("orders", "UNKNOWN")).status()).isEqualTo("UP");
    }

    @Test
    void drainingWinsOverWhateverTheMembersSay() {
        StackReadiness readiness = StackReadiness.of(true, Map.of("orders", "UP"));

        assertThat(readiness.status()).isEqualTo("DRAINING");
        assertThat(readiness.routable()).isFalse();
        assertThat(readiness.json()).isEqualTo("{\"status\":\"DRAINING\"}");
    }

    @Test
    void aNameIsQuotedForTheWire() {
        assertThat(StackReadiness.quote("受注")).isEqualTo("\"受注\"");
        assertThat(StackReadiness.quote("a\"b\\c")).isEqualTo("\"a\\\"b\\\\c\"");
        assertThat(StackReadiness.quote("tab\tname")).isEqualTo("\"tab\\u0009name\"");
    }
}
