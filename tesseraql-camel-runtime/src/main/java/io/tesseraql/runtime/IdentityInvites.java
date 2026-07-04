package io.tesseraql.runtime;

import io.tesseraql.core.credential.CredentialTokenStore;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.identity.IdentityContracts;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.yaml.notify.NotifyEvents;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The IAM admin's invite action (roadmap Phase 50 slice 2, design in
 * docs/credential-lifecycle.md): creates the user with status {@code INVITED} — which the
 * credential contract already refuses at login — then issues an invite token and mails the
 * accept link over the outbox. The operator never knows a password.
 */
final class IdentityInvites {

    /** Invitations not configured / not available on this deployment. */
    private static final TqlErrorCode UNCONFIGURED = new TqlErrorCode(TqlDomain.SEC, 4120);
    /** The login id is already taken. */
    private static final TqlErrorCode TAKEN = new TqlErrorCode(TqlDomain.SQL, 4090);
    /** Malformed invite input (missing login, unusable email). */
    private static final TqlErrorCode INVALID = new TqlErrorCode(TqlDomain.FIELD, 2001);

    private IdentityInvites() {
    }

    static Map<String, Object> invite(Map<String, Object> params,
            CredentialTokenStore tokens,
            io.tesseraql.operations.outbox.JdbcOutboxStore outbox, IdentityService identity,
            RealmConfig realm, String channel, String acceptUrl, Duration timeToLive,
            String appName, boolean enabled) {
        if (!enabled || tokens == null || identity == null || realm == null) {
            throw new TqlException(UNCONFIGURED,
                    "Invitations need tesseraql.identity.invite.{channel,url}");
        }
        String loginId = text(params.get("loginId"));
        String displayName = text(params.get("displayName"));
        String email = text(params.get("email"));
        if (loginId.isBlank() || loginId.length() > 255) {
            throw new TqlException(INVALID, "Invite needs a loginId");
        }
        if (!email.contains("@") || email.length() > 320) {
            throw new TqlException(INVALID, "Invite needs a mailable email");
        }
        var existing = identity.execute(realm, IdentityContracts.FIND_USER_BY_LOGIN,
                Map.of("loginId", loginId));
        if (!existing.isEmpty()) {
            // Re-inviting a still-INVITED account just re-mails (subject to the token
            // cooldown); anything already usable refuses - no silent account takeover.
            if (!"INVITED".equals(String.valueOf(existing.get(0).get("status")))) {
                throw new TqlException(TAKEN, "Login '" + loginId + "' already exists");
            }
        } else {
            identity.executeUpdate(realm, IdentityContracts.CREATE_USER, mapOf(
                    "userId", UUID.randomUUID().toString(),
                    "loginId", loginId,
                    "displayName", displayName.isBlank() ? loginId : displayName,
                    "email", email,
                    "status", "INVITED",
                    "tenantId", blankToNull(text(params.get("tenantId")))));
        }
        // Cooldown-aware: a repeated invite inside the window resends nothing (the first
        // mail is already out) and still answers ok - inviting twice is not an error.
        boolean mailed = tokens.issue(loginId, CredentialTokenStore.INVITE, timeToLive)
                .map(rawToken -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("to", email);
                    payload.put("loginId", loginId);
                    payload.put("displayName", displayName.isBlank() ? loginId : displayName);
                    payload.put("acceptUrl", acceptUrl + "?token="
                            + URLEncoder.encode(rawToken, StandardCharsets.UTF_8));
                    outbox.insert(NotifyEvents.event(channel, "identity.invite", payload,
                            appName));
                    return true;
                }).orElse(false);
        return Map.of("ok", true, "mailed", mailed);
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    private static String blankToNull(String value) {
        return value.isBlank() ? null : value;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
