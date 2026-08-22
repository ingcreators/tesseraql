package io.tesseraql.runtime;

import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.compiler.pipeline.Pipeline;
import io.tesseraql.compiler.pipeline.Pipelines;
import io.tesseraql.core.credential.CredentialTokenStore;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.identity.IdentityContracts;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.HttpMounts;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.security.password.Pbkdf2PasswordEncoder;
import io.tesseraql.security.session.SessionStore;
import io.tesseraql.yaml.notify.NotifyEvents;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
final class RecoveryRoutes {

    private static final System.Logger LOG = System.getLogger(RecoveryRoutes.class.getName());

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

    RecoveryRoutes(CredentialTokenStore tokens, IdentityService identity,
            RealmConfig realm, SessionStore sessions,
            io.tesseraql.operations.outbox.JdbcOutboxStore outbox, String channel,
            String confirmUrl, Duration timeToLive, String appName, boolean inviteEnabled,
            io.tesseraql.security.throttle.CredentialThrottle throttle) {
        this.tokens = tokens;
        this.throttle = throttle;
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

    private final io.tesseraql.security.throttle.CredentialThrottle throttle;

    void install(RuntimeContext context) {
        Pipelines.Compilation pipelines = Pipelines.of(context)
                .compiling(java.util.List.of(
                        Pipeline.Handler.catching(TqlException.class, new ErrorResponseRenderer()),
                        Pipeline.Handler.catching(Exception.class, new ErrorResponseRenderer())));

        if (channel != null && confirmUrl != null) {
            HttpMounts.of(context).mount("POST", "/_tesseraql/reset", "system.reset.request");
            pipelines.pipeline("system.reset.request")
                    .process(this::request);

            HttpMounts.of(context).mount("POST", "/_tesseraql/reset/confirm",
                    "system.reset.confirm");
            pipelines.pipeline("system.reset.confirm")
                    .process(this::confirm);
        }
        if (inviteEnabled) {
            // The invite accept leg (roadmap Phase 50 slice 2): same token machinery,
            // purpose invite, plus the enable-user flip to ACTIVE.
            HttpMounts.of(context).mount("POST", "/_tesseraql/invite",
                    "system.invite.accept");
            pipelines.pipeline("system.invite.accept")
                    .process(this::acceptInvite);
        }
    }

    /** Consume the invite token, set the first password, flip the account ACTIVE. */
    private void acceptInvite(Exchange exchange) throws Exception {
        Map<String, Object> body = LoginRoutes.parseBody(exchange);
        String token = str(body.get("token"));
        String next = str(body.get("next"));
        if (next.length() < 8 || next.length() > 256) {
            LoginRoutes.redirect(exchange, 303, "/_tesseraql/invite?error=short&token="
                    + URLEncoder.encode(token, StandardCharsets.UTF_8));
            return;
        }
        String address = LoginRoutes.presentedAddress(exchange);
        if (throttle.retryAfter("invite", null, address).isPresent()) {
            LoginRoutes.redirect(exchange, 303, "/_tesseraql/invite?invalid=1");
            return;
        }
        var consumed = tokens.consume(token, CredentialTokenStore.INVITE);
        if (consumed.isEmpty()) {
            throttle.recordFailure(null, address);
            LoginRoutes.redirect(exchange, 303, "/_tesseraql/invite?invalid=1");
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
        LoginRoutes.redirect(exchange, 303, "/_tesseraql/login?invited=1");
    }

    /** The neutral request leg: every outcome answers "sent". */
    private void request(Exchange exchange) throws Exception {
        Map<String, Object> body = LoginRoutes.parseBody(exchange);
        String loginId = str(body.get("loginId"));
        // Every request counts here - issuing mail IS the cost - and a throttled request
        // keeps the neutral answer: a 429 would itself be an oracle
        // (docs/credential-throttle.md). Only the issuing stops.
        String address = LoginRoutes.presentedAddress(exchange);
        boolean throttled = throttle.retryAfter("reset", loginId, address).isPresent();
        throttle.recordFailure(loginId, address);
        try {
            if (!loginId.isBlank() && !throttled) {
                issueAndMail(loginId);
            }
        } catch (RuntimeException ex) {
            // Deliberately swallowed into the neutral answer: a storage hiccup must not
            // become a different response for this login than for the next.
            LOG.log(System.Logger.Level.WARNING, "Reset issue failed: {0}", ex.toString());
        }
        LoginRoutes.redirect(exchange, 303, "/_tesseraql/reset?sent=1");
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
        Map<String, Object> body = LoginRoutes.parseBody(exchange);
        String token = str(body.get("token"));
        String next = str(body.get("next"));
        if (next.length() < 8 || next.length() > 256) {
            LoginRoutes.redirect(exchange, 303, "/_tesseraql/reset/confirm?error=short&token="
                    + URLEncoder.encode(token, StandardCharsets.UTF_8));
            return;
        }
        String address = LoginRoutes.presentedAddress(exchange);
        if (throttle.retryAfter("confirm", null, address).isPresent()) {
            LoginRoutes.redirect(exchange, 303, "/_tesseraql/reset/confirm?invalid=1");
            return;
        }
        var consumed = tokens.consume(token, CredentialTokenStore.RESET);
        if (consumed.isEmpty()) {
            throttle.recordFailure(null, address);
            // Unknown, used, and expired all land here - one honest dead-link answer.
            LoginRoutes.redirect(exchange, 303, "/_tesseraql/reset/confirm?invalid=1");
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
        LoginRoutes.redirect(exchange, 303, "/_tesseraql/login?reset=1");
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
