package io.tesseraql.runtime;

/**
 * What the development loop decides for a hosted stack, and production never does
 * (docs/cli-surface.md decisions 4 and 4b).
 *
 * <p>Two things only, both of which exist because {@code dev} can know them and {@code host}
 * cannot. The embedded database's coordinate — {@code --embedded-db} starts the server, so it is
 * not derived from any application, which is decision 4b's actual prohibition — and the default
 * external origin, which the development gateway knows by construction ({@code
 * http://localhost:<port>}) while an ingress-fronted host defaulting it would hand an MCP client
 * a {@code resource} of {@code localhost}, the silent misconfiguration Decision 6's
 * character-for-character rule exists to prevent.
 *
 * @param embeddedDb           the embedded server's coordinate, or {@code null} when the
 *                             development stack runs against real databases. When present it
 *                             supplies the framework datasource (shared database, no schema
 *                             qualifier — which closes the collision between TQL-APP-4211 and
 *                             per-application {@code currentSchema} isolation), and each
 *                             application's {@code main} pool is pointed at it carrying the
 *                             application's own declared query string. The explicit-declaration
 *                             refusal TQL-APP-4212 is deliberately scoped to stack-file supply
 *                             and does not fire here: "override everything" must not be the one
 *                             place an override is refused
 * @param defaultExternalOrigin the origin to fall back to when the stack file declares none —
 *                             the development gateway's own address, never a production guess
 */
public record DevMode(DataSources.MainDatasourceOverride embeddedDb,
        String defaultExternalOrigin) {
}
