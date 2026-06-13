package io.tesseraql.yaml.model;

import java.util.List;
import java.util.Map;

/**
 * Rendering hints for an MCP Apps UI resource (the MCP Apps extension, roadmap Phase 24), declared
 * under the {@code ui:} block of a {@code kind: ui} document and advertised verbatim as the
 * resource's {@code _meta.ui}. The host uses them when it sandboxes the rendered {@code hc-*}
 * fragment: {@code prefersBorder} asks the host to frame the embed, and the content-security-policy
 * domains widen the iframe's default deny so the fragment's htmx requests and asset loads resolve.
 *
 * @param prefersBorder      whether the host should frame the embed, or null to leave it to the host
 * @param cspConnectDomains  origins the fragment may issue requests to (htmx endpoints, fetch)
 * @param cspResourceDomains origins the fragment may load assets from (scripts, styles, images)
 */
public record UiSpec(Boolean prefersBorder, List<String> cspConnectDomains,
        List<String> cspResourceDomains) {

    public static final UiSpec EMPTY = new UiSpec(null, List.of(), List.of());

    public UiSpec {
        cspConnectDomains = cspConnectDomains == null ? List.of() : List.copyOf(cspConnectDomains);
        cspResourceDomains = cspResourceDomains == null
                ? List.of()
                : List.copyOf(cspResourceDomains);
    }

    /** Whether nothing was declared, so no {@code _meta.ui} need be emitted. */
    public boolean isEmpty() {
        return prefersBorder == null && cspConnectDomains.isEmpty() && cspResourceDomains.isEmpty();
    }

    /**
     * Builds a {@code UiSpec} from the loosely typed {@code ui:} block of a parsed YAML document
     * ({@code prefersBorder}, and {@code csp.connectDomains} / {@code csp.resourceDomains} string
     * lists). A non-map (or null) yields {@link #EMPTY}.
     */
    public static UiSpec from(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return EMPTY;
        }
        Boolean prefersBorder = map.get("prefersBorder") instanceof Boolean flag ? flag : null;
        List<String> connect = List.of();
        List<String> resource = List.of();
        if (map.get("csp") instanceof Map<?, ?> csp) {
            connect = stringList(csp.get("connectDomains"));
            resource = stringList(csp.get("resourceDomains"));
        }
        return new UiSpec(prefersBorder, connect, resource);
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
    }
}
