package io.tesseraql.runtime;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The boot facts a {@link io.tesseraql.compiler.ext.RuntimeExtension} may need beyond its
 * {@code ExtensionContext} (docs/studio-shell.md structural decision 3): what the inlined
 * Studio wiring used to reach as {@code TesseraqlRuntime.start(...)} locals, published as one
 * registry bean — {@code TesseraqlProperties.RUNTIME_SEAMS_BEAN} — before the extensions
 * install. The runtime publishes seams here, never a Studio type: every component is a
 * runtime-owned object or a plain value.
 *
 * @param port                  the app's own HTTP port (the try-it console's loopback target)
 * @param appName               the declared {@code tesseraql.app.name}
 * @param dataSources           every configured pool by datasource name ({@code main} included)
 * @param tenantDataSources     the per-tenant pool resolver (empty when untenanted)
 * @param calendarDecisions     the declared business calendars (the Studio calendar editor's model)
 * @param notificationChannels  the declared notification channels (the mail composer's targets)
 * @param reloader              the hot reloader {@code serve --watch} and Studio's apply share
 * @param postStart             registers a hook run after the platform HTTP server starts —
 *                              the SSE endpoints' registration window
 * @param httpOutbound          the deny-by-default egress allow-list every outbound call obeys
 * @param modulesLoader         the app's module class loader (docs/module-scope.md)
 * @param mainDatasourceDialect the main datasource's configured or inferred dialect id
 * @param hosted                whether a host is speaking at all ({@code false} on the unhosted
 *                              boot — integration tests, library embedding)
 * @param workshop              the host's workshop verdict (docs/studio-shell.md structural
 *                              decision 1): the development loop over source trees; always
 *                              {@code false} under {@code host} and on the unhosted boot it is
 *                              not consulted
 * @param stackMembers          the stack's member names, set only on the surface runtime's
 *                              seams (the shell's switcher lists them); {@code null} everywhere
 *                              else
 * @param memberOrigins         the host's live member-origin lookup, set only on the surface
 *                              runtime's seams — how a shell reaches a member's internal port
 *                              across replaces; {@code null} everywhere else
 */
public record RuntimeSeams(int port, String appName, Map<String, HikariDataSource> dataSources,
        TenantDataSources tenantDataSources, CalendarDecisions calendarDecisions,
        io.tesseraql.yaml.notify.NotificationChannels notificationChannels,
        RouteReloader reloader, Consumer<Runnable> postStart,
        io.tesseraql.yaml.http.HttpOutbound httpOutbound, ClassLoader modulesLoader,
        String mainDatasourceDialect, boolean hosted, boolean workshop,
        java.util.List<String> stackMembers, HostContext.MemberOrigins memberOrigins) {
}
