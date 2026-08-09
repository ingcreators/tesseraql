package io.tesseraql.yaml.model;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
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
        if (raw == null) {
            return EMPTY;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw invalid("ui: must be a block of rendering hints, got: " + raw);
        }
        Boolean prefersBorder = bool(map.get("prefersBorder"), "ui.prefersBorder");
        List<String> connect = List.of();
        List<String> resource = List.of();
        Object csp = map.get("csp");
        if (csp != null) {
            if (!(csp instanceof Map<?, ?> block)) {
                throw invalid("ui.csp: must be a block declaring connectDomains and/or"
                        + " resourceDomains, got: " + csp);
            }
            connect = stringList(block.get("connectDomains"), "ui.csp.connectDomains");
            resource = stringList(block.get("resourceDomains"), "ui.csp.resourceDomains");
        }
        return new UiSpec(prefersBorder, connect, resource);
    }

    /**
     * TQL-YAML-1026: a {@code ui:} value is not of the type the block declares.
     *
     * <p>Every read here used to be an {@code instanceof} that fell back to the default, so a
     * wrong-typed value disappeared: {@code prefersBorder: "true"} (a YAML string) left the hint
     * unset, and a {@code csp:} authored as a list — or a {@code connectDomains:} written as one
     * scalar host — left the sandbox at its default deny with the fragment's requests failing in
     * the host and nothing anywhere naming the config that was ignored.
     */
    private static final TqlErrorCode INVALID_UI = new TqlErrorCode(TqlDomain.YAML, 1026);

    private static TqlException invalid(String message) {
        return new TqlException(INVALID_UI, message);
    }

    private static Boolean bool(Object raw, String where) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Boolean flag) {
            return flag;
        }
        throw invalid(where + ": must be true or false, got: " + raw);
    }

    private static List<String> stringList(Object raw, String where) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw invalid(where + ": must be a list of origins, got: " + raw);
        }
        return list.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
    }
}
