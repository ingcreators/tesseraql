package io.tesseraql.yaml.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The boot side of the conditions zone (docs/audit-low-leads.md slice 8, XD-07f): the
 * runtime binds what {@link ConditionZone#of} answers, so a value that is not a zone is the
 * coded refusal naming the key — where it used to be {@code ZoneId.of}'s own
 * {@code ZoneRulesException}, wrapped by the boot as "Failed to start", naming nothing.
 */
class ConditionZoneTest {

    private static AppConfig config(String zone) {
        return new AppConfig(Map.of("tesseraql", Map.of("security", Map.of("conditions",
                zone == null ? Map.of() : Map.of("zone", zone)))), name -> null);
    }

    @Test
    void aMisspeltZoneIsRefusedNamingTheKey() {
        assertThatThrownBy(() -> ConditionZone.of(config("Asia/Tokio")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SEC-4147")
                .hasMessageContaining("tesseraql.security.conditions.zone: 'Asia/Tokio'")
                .hasMessageContaining("not a time-zone id the JDK knows");
    }

    @Test
    void aZoneIsBoundAsDeclaredAndAnAbsentOrBlankOneIsNotBound() {
        assertThat(ConditionZone.of(config("Asia/Tokyo"))).contains(ZoneId.of("Asia/Tokyo"));
        assertThat(ConditionZone.of(config("+09:00"))).contains(ZoneId.of("+09:00"));
        assertThat(ConditionZone.of(config(null))).isEmpty();
        assertThat(ConditionZone.of(config("  "))).isEmpty();
        assertThat(ConditionZone.problem(config("Asia/Tokyo"))).isEmpty();
        assertThat(ConditionZone.problem(config("JST"))).hasValueSatisfying(problem -> assertThat(
                problem).startsWith("tesseraql.security.conditions.zone: 'JST'"));
    }
}
