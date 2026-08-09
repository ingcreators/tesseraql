package io.tesseraql.runtime;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.identity.IdentityContracts;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.RealmConfig;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

/**
 * The IAM Admin bulk endpoint (docs/hypermedia-ui.md, "Bulk actions"): one action against
 * many selected users — the kit's datagrid-bulk-actions recipe posts the users list's
 * checkbox selection as repeated {@code ids} fields. A Java route because the Simple-YAML
 * input surface is deliberately single-valued; multi-value form parsing is a transport
 * concern, like the copilot's content negotiation. Same gate chain the YAML per-user
 * routes compile to: browser session, CSRF, {@code iam.admin.write} — and every submitted
 * id is validated by the identity contract itself. An id that matches no user updates
 * nothing and is <em>not</em> counted as disabled: the redirect carries what actually
 * changed alongside what was selected, so a stale selection cannot read as a completed
 * action (docs/silent-tolerance.md O10).
 */
final class IamAdminRouteBuilder extends RouteBuilder {

    private static final String USERS = "/_tesseraql/admin/users";

    @Override
    public void configure() {
        onException(TqlException.class).handled(true).process(new ErrorResponseRenderer());
        onException(Exception.class).handled(true).process(new ErrorResponseRenderer());

        rest().post(USERS + "/bulk").to("direct:tql.iamAdmin.users.bulk");

        // Post/redirect/get like the per-user disable: the no-JS-first shape of the
        // recipe (the htmx tbody-swap enhancement needs HX-Request negotiation and can
        // layer on later without changing this contract).
        from("direct:tql.iamAdmin.users.bulk").routeId("tql.iamAdmin.users.bulk")
                .to("tesseraql-auth:authenticate?auth=browser")
                .to("tesseraql-auth:csrf")
                .to("tesseraql-auth:authorize?policy=iam.admin.write")
                .process(exchange -> {
                    String action = formValues(exchange, "action").stream()
                            .findFirst().orElse("");
                    if (!"disable".equals(action)) {
                        throw new TqlException(new TqlErrorCode(TqlDomain.FIELD, 2001),
                                "Unknown bulk action: " + action);
                    }
                    Set<String> ids = new LinkedHashSet<>(formValues(exchange, "ids"));
                    if (ids.isEmpty()) {
                        throw new TqlException(new TqlErrorCode(TqlDomain.FIELD, 2001),
                                "Select at least one user");
                    }
                    IdentityService identity = exchange.getContext().getRegistry()
                            .lookupByNameAndType(TesseraqlProperties.IDENTITY_SERVICE_BEAN,
                                    IdentityService.class);
                    RealmConfig realm = exchange.getContext().getRegistry()
                            .lookupByNameAndType(TesseraqlProperties.IDENTITY_REALM_BEAN,
                                    RealmConfig.class);
                    io.tesseraql.security.session.SessionStore sessions = exchange.getContext()
                            .getRegistry().lookupByNameAndType(
                                    TesseraqlProperties.SESSION_STORE_BEAN,
                                    io.tesseraql.security.session.SessionStore.class);
                    // The same contract the per-user route runs, once per selected id. Disabled
                    // means disabled (docs/session-administration.md): every session of each
                    // subject ends now, not at cookie expiry.
                    //
                    // The affected-row count is kept rather than discarded: an id that matches
                    // no user updates nothing, and reporting the *requested* count made a stale
                    // selection read as a completed action ("5 user(s) disabled" having disabled
                    // three). The page is told both numbers and says so when they differ.
                    int disabled = 0;
                    for (String id : ids) {
                        if (identity.executeUpdate(realm, IdentityContracts.DISABLE_USER,
                                Map.of("userId", id)) > 0) {
                            disabled++;
                        }
                        sessions.invalidateOthersFor(id, "");
                    }
                    io.tesseraql.compiler.binding.RedirectRenderer.negotiate(exchange, 303,
                            USERS + "?bulk=" + disabled + "&selected=" + ids.size());
                });
    }

    /**
     * Every value of a repeated urlencoded form field. platform-http pre-parses a browser
     * form post into a Map body (the LoginRouteBuilder precedent) — a repeated field may
     * arrive as a collection value — and a raw string body is parsed by hand otherwise.
     */
    private static List<String> formValues(Exchange exchange, String name) {
        List<String> values = new ArrayList<>();
        Object body = exchange.getMessage().getBody();
        if (body instanceof Map<?, ?> form) {
            Object value = form.get(name);
            if (value instanceof Iterable<?> many) {
                many.forEach(each -> values.add(String.valueOf(each)));
            } else if (value != null) {
                values.add(String.valueOf(value));
            }
            return values;
        }
        String raw = exchange.getMessage().getBody(String.class);
        if (raw != null && !raw.isBlank()) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && name.equals(URLDecoder.decode(pair.substring(0, eq),
                        StandardCharsets.UTF_8))) {
                    values.add(URLDecoder.decode(pair.substring(eq + 1),
                            StandardCharsets.UTF_8));
                }
            }
        }
        return values;
    }
}
