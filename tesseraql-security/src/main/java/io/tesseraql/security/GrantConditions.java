package io.tesseraql.security;

import io.tesseraql.core.net.CidrBlock;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Grant-level context conditions (docs/access-governance.md structural decision 8): a held role
 * may carry conditions, and a grant whose conditions do not admit this request is dropped from
 * the active view.
 *
 * <p>This is {@link Activation}'s arithmetic with a different filter, deliberately. A dropped
 * grant's role leaves {@code roles} and its permissions leave {@code permissions} unless another
 * surviving grant or a direct grant delivers them, and {@code roleGrants} keeps only the
 * survivors — so activation, the role picker and every policy downstream see one narrowed set
 * rather than each re-deciding what a condition meant.
 *
 * <p><b>It narrows and never widens.</b> The frozen principal carries the conditions, the request
 * carries the context, and the worst a spoofed address can do is take capability away. That is
 * what makes the honest limit tolerable: the address is whatever the edge presented — as
 * {@code SessionStore.ClientInfo} already says — and the deployment allow-list checked at sign-in
 * is where a deployment gets an enforceable answer. Layer B is a policy control, not a security
 * boundary, and this class says so rather than implying otherwise.
 *
 * <p><b>Within a kind, any condition admits; across kinds, every kind must.</b> Two network
 * blocks are two offices, and a role usable from either is what an administrator naming both
 * means. A network condition and an hours condition together are two separate requirements, and
 * a role usable from the office but only in business hours is what naming both of those means.
 *
 * <p><b>A condition this runtime cannot read never admits.</b> An unknown kind and a malformed
 * value both drop the grant, because the alternative is a filter that silently stops filtering —
 * and a narrowing filter that fails closed can only ever cost capability, which is the direction
 * this whole mechanism is allowed to move in.
 */
public final class GrantConditions {

    /** A CIDR block the request's presented address must be inside. */
    public static final String NETWORK = "network";

    /** A local-time range, optionally limited to named days. */
    public static final String HOURS = "hours";

    private GrantConditions() {
    }

    /**
     * The principal with every conditioned grant that this request's context does not admit
     * removed, and roles and permissions recomputed to match.
     *
     * <p>A principal with no grants at all (a claim-asserted bearer, a {@code sql} realm without
     * the attribution contracts) has no attribution to narrow by and passes through untouched,
     * exactly as it does through activation. So does a principal whose grants carry no
     * conditions, which is every principal in a deployment that uses none.
     *
     * @param address the address the request presented, or null when there is none to judge
     * @param at      now, already in the deployment's configured zone
     */
    public static Principal narrow(Principal principal, String address, ZonedDateTime at) {
        if (principal == null || principal.roleGrants().isEmpty()) {
            return principal;
        }
        boolean anyConditioned = principal.roleGrants().stream()
                .anyMatch(grant -> !grant.conditions().isEmpty());
        if (!anyConditioned) {
            return principal;
        }
        List<Principal.RoleGrant> surviving = new ArrayList<>();
        for (Principal.RoleGrant grant : principal.roleGrants()) {
            if (admits(grant.conditions(), address, at)) {
                surviving.add(grant);
            }
        }
        if (surviving.size() == principal.roleGrants().size()) {
            return principal;
        }
        List<String> roles = new ArrayList<>();
        Set<String> permissions = new LinkedHashSet<>();
        for (Principal.RoleGrant grant : surviving) {
            roles.add(grant.role());
            permissions.addAll(grant.permissions());
        }
        permissions.addAll(principal.directPermissions());
        return new Principal(principal.subject(), principal.loginId(), principal.displayName(),
                principal.tenantId(), principal.groups(), roles, List.copyOf(permissions),
                principal.claims(), surviving, principal.directPermissions());
    }

    /** Whether this request's context satisfies every kind of condition on one grant. */
    public static boolean admits(List<Principal.RoleGrant.Condition> conditions, String address,
            ZonedDateTime at) {
        if (conditions.isEmpty()) {
            return true;
        }
        Map<String, Boolean> byKind = new LinkedHashMap<>();
        for (Principal.RoleGrant.Condition condition : conditions) {
            String kind = condition.kind() == null ? "" : condition.kind().trim();
            boolean satisfied = switch (kind) {
                case NETWORK -> insideNetwork(condition.value(), address);
                case HOURS -> insideHours(condition.value(), at);
                // A kind this runtime does not know cannot be judged, and an unjudged
                // condition is not a satisfied one.
                default -> false;
            };
            byKind.merge(kind, satisfied, (first, second) -> first || second);
        }
        return byKind.values().stream().allMatch(Boolean::booleanValue);
    }

    /** Whether the presented address is inside the block, with no address never inside one. */
    private static boolean insideNetwork(String value, String address) {
        if (value == null || address == null) {
            return false;
        }
        try {
            return CidrBlock.anyContains(List.of(CidrBlock.parse(value.trim())), address);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    /**
     * Whether {@code at} falls inside an hours condition such as {@code MON-FRI 09:00-18:00},
     * {@code SAT,SUN 10:00-16:00} or the day-less {@code 09:00-18:00}.
     *
     * <p>A range whose end is at or before its start runs past midnight — {@code MON-FRI
     * 22:00-06:00} is the night shift that starts on a weekday. The day set is matched against
     * the day the window <em>opens</em>, so that condition admits Saturday 05:00 (the Friday
     * window, still open) and refuses Monday 05:00 (the Sunday window, which was never open).
     */
    private static boolean insideHours(String value, ZonedDateTime at) {
        if (value == null || at == null) {
            return false;
        }
        Hours hours;
        try {
            hours = Hours.parse(value);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        LocalTime now = at.toLocalTime();
        DayOfWeek today = at.getDayOfWeek();
        if (hours.start().isBefore(hours.end())) {
            return hours.days().contains(today) && !now.isBefore(hours.start())
                    && now.isBefore(hours.end());
        }
        // Past midnight: either today's window has opened, or yesterday's has not yet closed.
        return (hours.days().contains(today) && !now.isBefore(hours.start()))
                || (hours.days().contains(today.minus(1)) && now.isBefore(hours.end()));
    }

    /**
     * A parsed hours condition. Public so an administrative write can refuse a malformed value
     * at the point of writing it rather than leaving a condition that silently never admits.
     */
    public record Hours(Set<DayOfWeek> days, LocalTime start, LocalTime end) {

        private static final List<String> NAMES = List.of("MON", "TUE", "WED", "THU", "FRI",
                "SAT", "SUN");

        public Hours {
            days = Set.copyOf(days);
        }

        /** Parses {@code [<days> ]<HH:MM>-<HH:MM>}; an absent day list means every day. */
        public static Hours parse(String value) {
            String[] parts = value.trim().split("\\s+");
            if (parts.length > 2) {
                throw new IllegalArgumentException("Not an hours condition: " + value
                        + ". Expected '09:00-18:00' or 'MON-FRI 09:00-18:00'.");
            }
            Set<DayOfWeek> days = parts.length == 2
                    ? parseDays(parts[0])
                    : EnumSet.allOf(DayOfWeek.class);
            String[] range = parts[parts.length - 1].split("-");
            if (range.length != 2) {
                throw new IllegalArgumentException("Not a time range: " + parts[parts.length - 1]
                        + ". Expected '09:00-18:00'.");
            }
            return new Hours(days, parseTime(range[0]), parseTime(range[1]));
        }

        private static Set<DayOfWeek> parseDays(String value) {
            Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
            for (String token : value.split(",")) {
                String entry = token.trim();
                if (entry.isEmpty()) {
                    continue;
                }
                int dash = entry.indexOf('-');
                if (dash < 0) {
                    days.add(day(entry));
                    continue;
                }
                // MON-FRI walks forward from the first day to the second, wrapping through
                // Sunday so FRI-MON is the long weekend rather than an empty set.
                DayOfWeek from = day(entry.substring(0, dash));
                DayOfWeek to = day(entry.substring(dash + 1));
                DayOfWeek cursor = from;
                days.add(cursor);
                while (cursor != to) {
                    cursor = cursor.plus(1);
                    days.add(cursor);
                }
            }
            if (days.isEmpty()) {
                throw new IllegalArgumentException("Not a day list: " + value
                        + ". Expected names such as 'MON-FRI' or 'SAT,SUN'.");
            }
            return days;
        }

        private static DayOfWeek day(String name) {
            int index = NAMES.indexOf(name.trim().toUpperCase(java.util.Locale.ROOT));
            if (index < 0) {
                throw new IllegalArgumentException("Not a day name: " + name
                        + ". Expected one of " + String.join(", ", NAMES) + ".");
            }
            return DayOfWeek.of(index + 1);
        }

        private static LocalTime parseTime(String value) {
            try {
                return LocalTime.parse(value.trim());
            } catch (java.time.format.DateTimeParseException invalid) {
                throw new IllegalArgumentException("Not a time of day: " + value
                        + ". Expected 24-hour 'HH:MM'.");
            }
        }
    }
}
