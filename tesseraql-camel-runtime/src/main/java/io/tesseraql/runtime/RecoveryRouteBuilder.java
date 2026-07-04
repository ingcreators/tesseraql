package io.tesseraql.runtime;

import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.core.credential.CredentialTokenStore;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.identity.IdentityContracts;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.security.password.Pbkdf2PasswordEncoder;
import io.tesseraql.security.session.SessionStore;
import io.tesseraql.yaml.notify.NotifyEvents;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

/**
 * Password-reset endpoints (roadmap Phase 50 slice 1, design in docs/credential-lifecycle.md):
 * <ul>
 *   <li>{@code POST /_tesseraql/reset} — always answers the same neutral "sent" whatever
 *       happened inside (unknown login, no email, cooldown): no enumeration oracle. When the
 *       account can be recovered, a one-time token is issued and the mail rides the outbox on
 *       the operator's channel.</li>
 *   <li>{@code POST /_tesseraql/reset/confirm} — consumes the token (single-use,
 *       check-and-set), writes the new hash through the {@code update-password} contract, and
 *       invalidates every session of the subject. Unknown, used, and expired tokens answer
 *       identically.</li>
 * </ul>
 * The pages themselves are the bundled auth-ui app's; only the state changes live here,
 * beside login/logout.
 */
final class RecoveryRouteBuilder extends RouteBuilder {

    private static final System.Logger LOG = System.getLogger(RecoveryRouteBuilder.class.getName());

    private final CredentialTokenStore tokens;
    private final IdentityService identity;
    private final RealmConfig realm;
    private final SessionStore sessions;
    private final io.tesseraql.operations.outbox.JdbcOutboxStore outbox;
    private final String channel;
    private final String confirmUrl;
    private final Duration timeToLive;
    private final String appName;

    private final boolean inviteEnabled;

    RecoveryRouteBuilder(CredentialTokenStore tokens, IdentityService identity,
            RealmConfig realm, SessionStore sessions,
            io.tesseraql.operations.outbox.JdbcOutboxStore outbox, String channel,
            String confirmUrl, Duration timeToLive, String appName, boolean inviteEnabled) {
        this.tokens = tokens;
        this.identity = identity;
        this.realm = realm;
        this.sessions = sessions;
        this.outbox = outbox;
        this.channel = channel;
        this.confirmUrl = confirmUrl;
        this.timeToLive = timeToLive;
        this.appName = appName;
        this.inviteEnabled = inviteEnabled;
    }

    @Override
    public void configure() {
        onException(TqlException.class).handled(true).process(new ErrorResponseRenderer());
        onException(Exception.class).handled(true).process(new ErrorResponseRenderer());

        if (channel != null && confirmUrl != null) {
            rest().post("/_tesseraql/reset").to("direct:tql.reset.request");
            from("direct:tql.reset.request").routeId("system.reset.request")
                    .process(this::request);

            rest().post("/_tesseraql/reset/confirm").to("direct:tql.reset.confirm");
            from("direct:tql.reset.confirm").routeId("system.reset.confirm")
                    .process(this::confirm);
        }
        if (inviteEnabled) {
            // The invite accept leg (roadmap Phase 50 slice 2): same token machinery,
            // purpose invite, plus the enable-user flip to ACTIVE.
            rest().post("/_tesseraql/invite").to("direct:tql.invite.accept");
            from("direct:tql.invite.accept").routeId("system.invite.accept")
                    .process(this::acceptInvite);
        }
    }

    /** Consume the invite token, set the first password, flip the account ACTIVE. */
    private void acceptInvite(Exchange exchange) throws Exception {
        Map<String, Object> body = LoginRouteBuilder.parseBody(exchange);
        String token = str(body.get("token"));
        String next = str(body.get("next"));
        if (next.length() < 8 || next.length() > 256) {
            LoginRouteBuilder.redirect(exchange, 303, "/_tesseraql/invite?error=short&token="
                    + URLEncoder.encode(token, StandardCharsets.UTF_8));
            return;
        }
        var consumed = tokens.consume(token, CredentialTokenStore.INVITE);
        if (consumed.isEmpty()) {
            LoginRouteBuilder.redirect(exchange, 303, "/_tesseraql/invite?invalid=1");
            return;
        }
        String loginId = consumed.get();
        Pbkdf2PasswordEncoder encoder = new Pbkdf2PasswordEncoder();
        identity.executeUpdate(realm, IdentityContracts.UPDATE_PASSWORD, Map.of(
                "loginId", loginId,
                "passwordHash", encoder.encode(next),
                "passwordParams", encoder.defaultParams()));
        List<Map<String, Object>> users = identity.execute(realm,
                IdentityContracts.FIND_USER_BY_LOGIN, Map.of("loginId", loginId));
        if (!users.isEmpty()) {
            identity.executeUpdate(realm, IdentityContracts.ENABLE_USER,
                    Map.of("userId", str(users.get(0).get("user_id"))));
        }
        LoginRouteBuilder.redirect(exchange, 303, "/_tesseraql/login?invited=1");
    }

    /** The neutral request leg: every outcome answers "sent". */
    private void request(Exchange exchange) throws Exception {
        Map<String, Object> body = LoginRouteBuilder.parseBody(exchange);
        String loginId = str(body.get("loginId"));
        try {
            if (!loginId.isBlank()) {
                issueAndMail(loginId);
            }
        } catch (RuntimeException ex) {
            // Deliberately swallowed into the neutral answer: a storage hiccup must not
            // become a different response for this login than for the next.
            LOG.log(System.Logger.Level.WARNING, "Reset issue failed: {0}", ex.toString());
        }
        LoginRouteBuilder.redirect(exchange, 303, "/_tesseraql/reset?sent=1");
    }

    private void issueAndMail(String loginId) {
        List<Map<String, Object>> destinations = identity.execute(realm,
                IdentityContracts.FIND_RECOVERY_DESTINATION, Map.of("loginId", loginId));
        if (destinations.isEmpty()) {
            return;
        }
        Map<String, Object> destination = destinations.get(0);
        String address = str(destination.get("destination"));
        if (address.isBlank()) {
            return;
        }
        tokens.issue(loginId, CredentialTokenStore.RESET, timeToLive).ifPresent(rawToken -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("to", address);
            payload.put("loginId", loginId);
            payload.put("displayName", str(destination.get("display_name")));
            payload.put("resetUrl", confirmUrl + "?token="
                    + URLEncoder.encode(rawToken, StandardCharsets.UTF_8));
            outbox.insert(NotifyEvents.event(channel, "identity.reset", payload, appName));
        });
    }

    /** The confirm leg: consume, rotate, kill every session of the subject. */
    private void confirm(Exchange exchange) throws Exception {
        Map<String, Object> body = LoginRouteBuilder.parseBody(exchange);
        String token = str(body.get("token"));
        String next = str(body.get("next"));
        if (next.length() < 8 || next.length() > 256) {
            LoginRouteBuilder.redirect(exchange, 303, "/_tesseraql/reset/confirm?error=short&token="
                    + URLEncoder.encode(token, StandardCharsets.UTF_8));
            return;
        }
        var consumed = tokens.consume(token, CredentialTokenStore.RESET);
        if (consumed.isEmpty()) {
            // Unknown, used, and expired all land here - one honest dead-link answer.
            LoginRouteBuilder.redirect(exchange, 303, "/_tesseraql/reset/confirm?invalid=1");
            return;
        }
        String loginId = consumed.get();
        Pbkdf2PasswordEncoder encoder = new Pbkdf2PasswordEncoder();
        identity.executeUpdate(realm, IdentityContracts.UPDATE_PASSWORD, Map.of(
                "loginId", loginId,
                "passwordHash", encoder.encode(next),
                "passwordParams", encoder.defaultParams()));
        // A consumed reset ends every session of the subject - the keep-nothing form of
        // the Phase 48 sign-out-others.
        List<Map<String, Object>> users = identity.execute(realm,
                IdentityContracts.FIND_USER_BY_LOGIN, Map.of("loginId", loginId));
        if (!users.isEmpty()) {
            sessions.invalidateOthersFor(str(users.get(0).get("user_id")), "");
        }
        LoginRouteBuilder.redirect(exchange, 303, "/_tesseraql/login?reset=1");
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
