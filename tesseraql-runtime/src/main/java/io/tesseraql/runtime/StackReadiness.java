package io.tesseraql.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The origin's readiness, rolled up over the runtimes the host serves
 * (docs/deployment-maturity.md decision 3).
 *
 * <p>The origin path answered from the gateway's own {@code draining} flag alone, so the path
 * the Kamal template and the container image probe never looked at a member: a stack whose only
 * application could not reach its database kept receiving traffic and answered each request with
 * a 500 after thirty seconds. Now the answer is {@code DRAINING} while the stack stops,
 * {@code DOWN} when <em>every</em> member is, {@code DEGRADED} when any is not {@code UP}, and
 * {@code UP} otherwise; the body names what is down and what is warning.
 *
 * <p>Every member, not any: two replicas share every member's database, so when application
 * A's database is down it is down on both pods, and a readiness that fails on any {@code DOWN}
 * member empties the Service and takes application B down with it. A partial outage is a
 * routable pod; the per-member path stays the per-application truth for an edge that
 * health-checks per prefix. A member with no roll-up yet is neither — it is unmeasured, and the
 * poll that found it so has started its first roll-up.
 */
record StackReadiness(String status, List<String> down, List<String> warn) {

    /** The roll-up over {@code members}, each keyed by its slot name with its held status. */
    static StackReadiness of(boolean draining, Map<String, String> members) {
        if (draining) {
            return new StackReadiness("DRAINING", List.of(), List.of());
        }
        List<String> down = new ArrayList<>();
        List<String> warn = new ArrayList<>();
        int measured = 0;
        for (Map.Entry<String, String> member : new TreeMap<>(members).entrySet()) {
            switch (member.getValue()) {
                case "DOWN" -> {
                    down.add(member.getKey());
                    measured++;
                }
                case "UP" -> measured++;
                case "UNKNOWN" -> {
                    // No roll-up yet; the poll started one. Unmeasured is not degraded.
                }
                default -> {
                    warn.add(member.getKey());
                    measured++;
                }
            }
        }
        String status = measured > 0 && down.size() == measured
                ? "DOWN"
                : !down.isEmpty() || !warn.isEmpty()
                        ? "DEGRADED"
                        : "UP";
        return new StackReadiness(status, List.copyOf(down), List.copyOf(warn));
    }

    /** Whether new traffic should be routed here: neither draining nor every member down. */
    boolean routable() {
        return !"DRAINING".equals(status) && !"DOWN".equals(status);
    }

    /** The body, hand-written like the constants it replaces; empty lists are omitted. */
    String json() {
        StringBuilder body = new StringBuilder("{\"status\":\"").append(status).append('"');
        append(body, "down", down);
        append(body, "warn", warn);
        return body.append('}').toString();
    }

    private static void append(StringBuilder body, String key, List<String> names) {
        if (names.isEmpty()) {
            return;
        }
        body.append(",\"").append(key).append("\":[");
        for (int i = 0; i < names.size(); i++) {
            body.append(i == 0 ? "" : ",").append(quote(names.get(i)));
        }
        body.append(']');
    }

    /** A JSON string: the name grammar admits letters the wire must not misread as syntax. */
    static String quote(String text) {
        StringBuilder quoted = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) c));
                    } else {
                        quoted.append(c);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }
}
