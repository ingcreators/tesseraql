package io.tesseraql.operations.app;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * A record of an app installed from a {@code .tqlapp} package (design ch. 32.4, 32.5).
 *
 * <p>The application's identity is its <b>name</b> — the same string {@code tesseraql.app.name}
 * declares, that {@code ops.app.<name>} grants are checked against, and that the stack addresses
 * the application by. The catalogue used to call this field {@code id}; the two were the same
 * string by construction everywhere in the tree, and two names for one thing is the defect
 * docs/cli-surface.md opens with, so the synonym is gone. Pre-1.0 format change: a
 * {@code catalog.json} written before the rename spells the field {@code "id"} and is refused
 * with a message naming it.
 *
 * <h2>The address is derived, always</h2>
 *
 * <p>An application answers at {@code /<name>} (docs/stack-architecture.md Decision 25): the name
 * grammar — no leading underscore or dot, no slash, the segment-safety lint and boot rule — already fences
 * {@code /_tesseraql/} and the dotted root names, so the old {@code /apps/} wrapper defended
 * nothing, and {@code /orders/invoice/123} is the address a person would have guessed. There is no
 * {@code basePath} field in {@code catalog.json} any more, and a catalogue that still carries one
 * is refused rather than quietly re-addressed: what remained of a declarable address was the
 * vanity rename, and a renamed address breaks every neighbour's links. Installing a new version of
 * an application cannot change where it answers, because <em>nothing</em> can. The deployment's
 * root choice is Decision 24's redirect, not an address override.
 *
 * @param name            the application's name (from {@code tesseraql.app.name})
 * @param version         the app version (from {@code tesseraql.app.version}, or {@code 0.0.0})
 * @param path            the install directory, app-relative to the install root
 * @param entitledTenants tenants allowed to use this app; empty means all tenants (ch. 32.8)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InstalledApp(String name, String version, String path,
        List<String> entitledTenants) {

    public InstalledApp {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A catalogue entry has no name. The field is"
                    + " \"name\" — a catalog.json written before the rename spells it \"id\" and"
                    + " must be rewritten (pre-1.0 format change, see the CHANGELOG).");
        }
        entitledTenants = entitledTenants == null ? List.of() : List.copyOf(entitledTenants);
    }

    /**
     * The JSON shape, which refuses a declared {@code basePath} loudly: the address is derived
     * from the name, always, and silently ignoring a declaration would leave an operator
     * believing in an address nothing serves.
     */
    @JsonCreator
    static InstalledApp fromJson(@JsonProperty("name") String name,
            @JsonProperty("version") String version,
            @JsonProperty("path") String path,
            @JsonProperty("entitledTenants") List<String> entitledTenants,
            @JsonProperty("basePath") String basePath) {
        if (basePath != null) {
            throw new IllegalArgumentException("Entry '" + name + "' declares basePath '"
                    + basePath + "', and addresses are not declarable: an application answers at"
                    + " /<name>, always (docs/stack-architecture.md Decision 25). Remove the"
                    + " field; the deployment's root choice is the root redirect, not an address"
                    + " override.");
        }
        return new InstalledApp(name, version, path, entitledTenants);
    }

    /**
     * The prefix this application is addressed under and serves at: {@code /<name>}, derived —
     * this method is the one producer of an application's address, so an install, an upgrade and
     * a neighbour's link can never disagree about it. The segment-safety rule on
     * {@code tesseraql.app.name} keeps every name a single safe segment, which is what makes the
     * derivation total.
     */
    public String basePath() {
        return "/" + name;
    }

    /** Whether {@code tenantId} may use this app (entitled to all, or explicitly listed). */
    public boolean isEntitled(String tenantId) {
        return entitledTenants.isEmpty() || entitledTenants.contains(tenantId);
    }
}
