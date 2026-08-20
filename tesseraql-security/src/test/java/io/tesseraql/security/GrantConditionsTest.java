package io.tesseraql.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.security.Principal.RoleGrant;
import io.tesseraql.security.Principal.RoleGrant.Condition;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Grant context conditions (docs/access-governance.md structural decision 8, layer B). */
class GrantConditionsTest {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    /** A Wednesday at 10:00 local. */
    private static final ZonedDateTime WEDNESDAY_MORNING = ZonedDateTime.of(2026, 8, 19, 10, 0, 0,
            0, TOKYO);

    @Test
    void anUnconditionedPrincipalIsReturnedUntouched() {
        Principal principal = principal(grant("orders.buyer", "orders", List.of("orders.read")));

        assertThat(GrantConditions.narrow(principal, "203.0.113.9", WEDNESDAY_MORNING))
                .isSameAs(principal);
    }

    /** The compatibility default activation already keeps: no attribution, nothing to narrow. */
    @Test
    void aPrincipalWithoutGrantsPassesThrough() {
        Principal claimAsserted = new Principal("s", "kenji", "Kenji", null, List.of(),
                List.of("orders.buyer"), List.of("orders.read"), Map.of());

        assertThat(GrantConditions.narrow(claimAsserted, "10.0.0.1", WEDNESDAY_MORNING))
                .isSameAs(claimAsserted);
    }

    @Test
    void aGrantInsideItsNetworkSurvivesAndOutsideItDoesNot() {
        Principal principal = principal(grant("orders.approver", "orders",
                List.of("orders.approve"), new Condition("network", "10.0.0.0/8")));

        assertThat(GrantConditions.narrow(principal, "10.4.5.6", WEDNESDAY_MORNING).roles())
                .containsExactly("orders.approver");
        Principal outside = GrantConditions.narrow(principal, "203.0.113.9", WEDNESDAY_MORNING);
        assertThat(outside.roles()).isEmpty();
        assertThat(outside.permissions()).isEmpty();
        assertThat(outside.roleGrants()).as("the picker must not offer what conditions denied")
                .isEmpty();
    }

    /** The design's stated shape: the role leaves, its permissions leave with it. */
    @Test
    void aSurvivingGrantOrADirectGrantStillDeliversTheSamePermission() {
        Principal twoPaths = new Principal("s", "kenji", "Kenji", null, List.of(),
                List.of("orders.approver", "orders.reader"),
                List.of("orders.approve", "orders.read"), Map.of(),
                List.of(grant("orders.approver", "orders", List.of("orders.approve",
                        "orders.read"), new Condition("network", "10.0.0.0/8")),
                        grant("orders.reader", "orders", List.of("orders.read"))),
                List.of());

        Principal outside = GrantConditions.narrow(twoPaths, "203.0.113.9", WEDNESDAY_MORNING);

        assertThat(outside.roles()).containsExactly("orders.reader");
        assertThat(outside.permissions()).as("read survives on the other grant, approve does not")
                .containsExactly("orders.read");
    }

    @Test
    void aDirectPermissionIsNotTakenAwayByAConditionOnARole() {
        Principal principal = new Principal("s", "kenji", "Kenji", null, List.of(),
                List.of("orders.approver"), List.of("orders.approve", "orders.read"), Map.of(),
                List.of(grant("orders.approver", "orders", List.of("orders.approve"),
                        new Condition("network", "10.0.0.0/8"))),
                List.of("orders.read"));

        assertThat(GrantConditions.narrow(principal, "203.0.113.9", WEDNESDAY_MORNING)
                .permissions()).containsExactly("orders.read");
    }

    /** Two offices: either admits. Two kinds: both must. */
    @Test
    void anyConditionOfAKindAdmitsWhileEveryKindMust() {
        List<Condition> twoOffices = List.of(new Condition("network", "10.0.0.0/8"),
                new Condition("network", "192.168.0.0/16"));
        assertThat(GrantConditions.admits(twoOffices, "192.168.4.4", WEDNESDAY_MORNING)).isTrue();
        assertThat(GrantConditions.admits(twoOffices, "172.16.0.1", WEDNESDAY_MORNING)).isFalse();

        List<Condition> officeHours = List.of(new Condition("network", "10.0.0.0/8"),
                new Condition("hours", "MON-FRI 09:00-18:00"));
        assertThat(GrantConditions.admits(officeHours, "10.0.0.1", WEDNESDAY_MORNING)).isTrue();
        assertThat(GrantConditions.admits(officeHours, "192.168.4.4", WEDNESDAY_MORNING))
                .as("the hours are satisfied and the network is not").isFalse();
    }

    @Test
    void hoursAreMatchedOnTheDayAndTheTimeOfDay() {
        List<Condition> weekdays = List.of(new Condition("hours", "MON-FRI 09:00-18:00"));

        assertThat(GrantConditions.admits(weekdays, null, WEDNESDAY_MORNING)).isTrue();
        assertThat(GrantConditions.admits(weekdays, null, WEDNESDAY_MORNING.withHour(8)))
                .isFalse();
        assertThat(GrantConditions.admits(weekdays, null, WEDNESDAY_MORNING.withHour(18)))
                .as("the end of the range is exclusive").isFalse();
        assertThat(GrantConditions.admits(weekdays, null, WEDNESDAY_MORNING.plusDays(3)))
                .as("Saturday").isFalse();
    }

    @Test
    void aDayLessRangeAppliesEveryDay() {
        List<Condition> always = List.of(new Condition("hours", "09:00-18:00"));

        assertThat(GrantConditions.admits(always, null, WEDNESDAY_MORNING.plusDays(3))).isTrue();
        assertThat(GrantConditions.admits(always, null, WEDNESDAY_MORNING.withHour(3))).isFalse();
    }

    /** The night shift: the day set is matched against the day the window opens. */
    @Test
    void aRangePastMidnightBelongsToTheDayItOpenedOn() {
        List<Condition> nights = List.of(new Condition("hours", "MON-FRI 22:00-06:00"));

        assertThat(GrantConditions.admits(nights, null, WEDNESDAY_MORNING.withHour(23))).isTrue();
        assertThat(GrantConditions.admits(nights, null, WEDNESDAY_MORNING.plusDays(3).withHour(5)))
                .as("Saturday 05:00 is Friday's window, still open").isTrue();
        assertThat(GrantConditions.admits(nights, null, WEDNESDAY_MORNING.plusDays(5).withHour(5)))
                .as("Monday 05:00 is Sunday's window, which never opened").isFalse();
    }

    @Test
    void aCommaSeparatedDayListIsAccepted() {
        List<Condition> weekend = List.of(new Condition("hours", "SAT,SUN 10:00-16:00"));

        assertThat(
                GrantConditions.admits(weekend, null, WEDNESDAY_MORNING.plusDays(3).withHour(12)))
                .isTrue();
        assertThat(GrantConditions.admits(weekend, null, WEDNESDAY_MORNING.withHour(12))).isFalse();
    }

    /** Fail closed: an unreadable condition can only cost capability, never grant it. */
    @Test
    void anUnknownKindOrAMalformedValueNeverAdmits() {
        assertThat(GrantConditions.admits(List.of(new Condition("device", "managed")),
                "10.0.0.1", WEDNESDAY_MORNING)).isFalse();
        assertThat(GrantConditions.admits(List.of(new Condition("network", "10.0.0.0/99")),
                "10.0.0.1", WEDNESDAY_MORNING)).isFalse();
        assertThat(GrantConditions.admits(List.of(new Condition("hours", "always")),
                "10.0.0.1", WEDNESDAY_MORNING)).isFalse();
    }

    /** A request with no address to judge is not inside any network. */
    @Test
    void anAbsentAddressIsNeverInsideANetwork() {
        assertThat(GrantConditions.admits(List.of(new Condition("network", "10.0.0.0/8")), null,
                WEDNESDAY_MORNING)).isFalse();
    }

    private static Principal principal(RoleGrant... grants) {
        List<String> roles = java.util.Arrays.stream(grants).map(RoleGrant::role).toList();
        List<String> permissions = java.util.Arrays.stream(grants)
                .flatMap(grant -> grant.permissions().stream()).distinct().toList();
        return new Principal("s", "kenji", "Kenji", null, List.of(), roles, permissions,
                Map.of(), List.of(grants), List.of());
    }

    private static RoleGrant grant(String role, String application, List<String> permissions,
            Condition... conditions) {
        return new RoleGrant(role, application, permissions, List.of(conditions));
    }
}
