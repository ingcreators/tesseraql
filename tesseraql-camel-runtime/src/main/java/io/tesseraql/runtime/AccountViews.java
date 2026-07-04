package io.tesseraql.runtime;

import io.tesseraql.core.account.PreferenceStore;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.identity.IdentityContracts;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.PasswordAuthenticator;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.security.password.Pbkdf2PasswordEncoder;
import io.tesseraql.security.session.SessionStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Template-ready models and settings writers for the bundled account surface (roadmap
 * Phase 48). The routes map the session principal's facts into the service params — a
 * provider never resolves a subject itself, so it can only ever describe, or write for,
 * the caller.
 */
final class AccountViews {

    private static final TqlErrorCode INVALID_VALUE = new TqlErrorCode(TqlDomain.ACCOUNT, 4802);
    /** Password change unavailable: SSO-managed credentials or no local identity realm. */
    private static final TqlErrorCode PASSWORD_UNAVAILABLE = new TqlErrorCode(TqlDomain.ACCOUNT,
            4803);
    private static final TqlErrorCode WRONG_PASSWORD = new TqlErrorCode(TqlDomain.ACCOUNT, 4804);

    private AccountViews() {
    }

    /** The profile page model: who the system thinks the signed-in user is. */
    static Map<String, Object> profile(Map<String, Object> params) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", text(params.get("displayName"), text(params.get("loginId"), "")));
        profile.put("loginId", text(params.get("loginId"), ""));
        profile.put("subject", text(params.get("subject"), ""));
        profile.put("tenantId", text(params.get("tenantId"), ""));
        profile.put("roles", strings(params.get("roles")));
        profile.put("permissions", strings(params.get("permissions")));
        return profile;
    }

    /** The account page model: profile plus every settings section's state. */
    static Map<String, Object> settings(Map<String, Object> params, PreferenceStore preferences,
            List<String> supportedTags, List<String> optOutChannels, SessionStore sessions,
            boolean passwordEnabled, io.tesseraql.yaml.account.PreferencesSpec appSpec,
            io.tesseraql.core.credential.TotpStore totp, String issuer,
            io.tesseraql.core.workflow.DelegationStore delegations) {
        Map<String, Object> model = profile(params);
        Map<String, String> stored = preferences == null
                ? Map.of()
                : preferences.preferences(tenant(params), subject(params));
        List<Map<String, Object>> notifyChannels = new ArrayList<>();
        for (String channel : optOutChannels) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", channel);
            entry.put("optedOut",
                    "true".equals(stored.get("notify." + channel + ".optOut")));
            notifyChannels.add(entry);
        }
        model.put("notifyChannels", notifyChannels);
        // The self-service session list (roadmap Phase 48 slice 4): timestamps only - the
        // session ids never reach the template. In-memory stores have no expiry to show.
        List<Map<String, Object>> sessionRows = new ArrayList<>();
        if (sessions != null) {
            for (SessionStore.ActiveSession active : sessions.sessionsFor(subject(params))) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("createdAt",
                        active.createdAt() == null ? "" : active.createdAt().toString());
                row.put("expiresAt",
                        active.expiresAt() == null ? "" : active.expiresAt().toString());
                sessionRows.add(row);
            }
        }
        model.put("sessions", sessionRows);
        model.put("passwordEnabled", passwordEnabled);
        // Declared app preference groups (config/preferences.yml, slice 5): current value =
        // stored app.<key> else the declared default; only declared keys render or save.
        List<Map<String, Object>> appPreferences = new ArrayList<>();
        for (io.tesseraql.yaml.account.PreferencesSpec.Field field : appSpec.fields()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", field.key());
            entry.put("label", field.label());
            entry.put("type", field.type());
            entry.put("options", field.options());
            String value = stored.get("app." + field.key());
            entry.put("value", value != null
                    ? value
                    : field.defaultValue() != null ? field.defaultValue() : "");
            appPreferences.add(entry);
        }
        model.put("appPreferences", appPreferences);
        // TOTP state (roadmap Phase 50 slice 3): confirmed = enforced at login; a pending
        // (unconfirmed) enrollment renders its secret and otpauth URI to its owner only.
        Map<String, Object> totpModel = new LinkedHashMap<>();
        totpModel.put("available", totp != null && passwordEnabled);
        totpModel.put("enrolled", false);
        if (totp != null) {
            totp.enrollment(tenant(params), subject(params)).ifPresent(enrollment -> {
                totpModel.put("enrolled", enrollment.confirmed());
                if (!enrollment.confirmed()) {
                    totpModel.put("pendingSecret", enrollment.secret());
                    totpModel.put("otpauth", io.tesseraql.security.totp.Totp.otpauthUri(
                            issuer == null ? "TesseraQL" : issuer,
                            text(params.get("loginId"), subject(params)),
                            enrollment.secret()));
                }
            });
        }
        model.put("totp", totpModel);
        // Out-of-office state (roadmap Phase 52): the card renders only where workflows
        // assign tasks (the store binds there); the rule is strictly the caller's own.
        Map<String, Object> delegationModel = new LinkedHashMap<>();
        delegationModel.put("available", delegations != null);
        if (delegations != null) {
            delegations.rule(tenant(params), subject(params)).ifPresent(rule -> {
                delegationModel.put("delegate", rule.delegateSubject());
                delegationModel.put("startsAt", rule.startsAt().toString());
                delegationModel.put("endsAt", rule.endsAt().toString());
                delegationModel.put("active", rule.covers(java.time.Instant.now()));
            });
        }
        model.put("delegation", delegationModel);
        String locale = stored.get("ui.locale");
        List<Map<String, Object>> locales = new ArrayList<>();
        for (String tag : supportedTags) {
            Locale asLocale = Locale.forLanguageTag(tag);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("tag", tag);
            // The language names itself in its own locale ("日本語", not "Japanese").
            entry.put("label", asLocale.getDisplayName(asLocale));
            entry.put("selected", tag.equals(locale));
            locales.add(entry);
        }
        model.put("locales", locales);
        model.put("theme", stored.getOrDefault("ui.theme", ""));
        return model;
    }

    /** Persists {@code ui.locale}; the tag must be one the app actually serves. */
    static Map<String, Object> saveLanguage(Map<String, Object> params,
            PreferenceStore preferences, List<String> supportedTags) {
        String locale = text(params.get("locale"), "");
        if (!supportedTags.contains(locale)) {
            throw new TqlException(INVALID_VALUE,
                    "Unsupported locale '" + locale + "' — the app serves " + supportedTags);
        }
        preferences.put(tenant(params), subject(params), "ui.locale", locale);
        return Map.of("ok", true);
    }

    /** Persists {@code ui.theme}; the route's enum input already constrained the value. */
    static Map<String, Object> saveTheme(Map<String, Object> params,
            PreferenceStore preferences) {
        String theme = text(params.get("theme"), "");
        if (!"light".equals(theme) && !"dark".equals(theme)) {
            throw new TqlException(INVALID_VALUE, "Unsupported theme '" + theme + "'");
        }
        preferences.put(tenant(params), subject(params), "ui.theme", theme);
        return Map.of("ok", true);
    }

    /**
     * Persists a channel opt-out choice; only channels the operator marked user-facing
     * ({@code userOptOut: true}) are writable. Opting back in removes the row.
     */
    static Map<String, Object> saveNotifyOptOut(Map<String, Object> params,
            PreferenceStore preferences, List<String> optOutChannels) {
        String channel = text(params.get("channel"), "");
        if (!optOutChannels.contains(channel)) {
            throw new TqlException(INVALID_VALUE,
                    "Channel '" + channel + "' does not offer a user opt-out");
        }
        boolean optOut = Boolean.parseBoolean(text(params.get("optOut"), "false"));
        String key = "notify." + channel + ".optOut";
        if (optOut) {
            preferences.put(tenant(params), subject(params), key, "true");
        } else {
            preferences.remove(tenant(params), subject(params), key);
        }
        return Map.of("ok", true);
    }

    /**
     * Rotates the caller's local-realm password: verify the current credential through the
     * same contract the login path uses, then write the new hash. Registered lazily against
     * the registry so SSO-only deployments answer with the honest 4803 instead of a stub.
     */
    static Map<String, Object> changePassword(Map<String, Object> params,
            IdentityService identity, RealmConfig realm, boolean passwordEnabled) {
        if (!passwordEnabled || identity == null || realm == null) {
            throw new TqlException(PASSWORD_UNAVAILABLE,
                    "Credentials are managed by your identity provider");
        }
        String loginId = text(params.get("loginId"), "");
        String current = text(params.get("current"), "");
        String next = text(params.get("next"), "");
        if (new PasswordAuthenticator(identity)
                .authenticate(realm, loginId, current, tenant(params)).isEmpty()) {
            throw new TqlException(WRONG_PASSWORD, "The current password does not match");
        }
        Pbkdf2PasswordEncoder encoder = new Pbkdf2PasswordEncoder();
        identity.executeUpdate(realm, IdentityContracts.UPDATE_PASSWORD, Map.of(
                "loginId", loginId,
                "passwordHash", encoder.encode(next),
                "passwordParams", encoder.defaultParams()));
        return Map.of("ok", true);
    }

    /** Persists one declared app preference; the declaration bounds key and value alike. */
    static Map<String, Object> saveAppPreference(Map<String, Object> params,
            PreferenceStore preferences, io.tesseraql.yaml.account.PreferencesSpec appSpec) {
        String key = text(params.get("key"), "");
        io.tesseraql.yaml.account.PreferencesSpec.Field field = appSpec.field(key);
        if (field == null) {
            throw new TqlException(INVALID_VALUE, "No declared preference named '" + key + "'");
        }
        String value = text(params.get("value"),
                "boolean".equals(field.type()) ? "false" : "");
        if (!io.tesseraql.yaml.account.PreferencesSpec.accepts(field, value)) {
            throw new TqlException(INVALID_VALUE,
                    "Value '" + value + "' is not acceptable for preference '" + key + "'");
        }
        preferences.put(tenant(params), subject(params), "app." + key, value);
        return Map.of("ok", true);
    }

    /** The inbox page model; an app without an inbox channel renders the honest empty state. */
    static Map<String, Object> inbox(Map<String, Object> params,
            io.tesseraql.core.inbox.InboxStore inbox) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("enabled", inbox != null);
        List<Map<String, Object>> messages = new ArrayList<>();
        int unread = 0;
        if (inbox != null) {
            String tenant = tenant(params);
            String subject = subject(params);
            unread = inbox.unreadCount(tenant, subject);
            for (io.tesseraql.core.inbox.InboxStore.InboxMessage message : inbox
                    .recent(tenant, subject, 100)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("eventId", message.eventId());
                row.put("title", message.title());
                row.put("body", message.body() == null ? "" : message.body());
                row.put("source", message.source());
                row.put("createdAt", message.createdAt().toString());
                row.put("read", message.readAt() != null);
                messages.add(row);
            }
        }
        model.put("unread", unread);
        model.put("messages", messages);
        return model;
    }

    /** Marks one of the caller's messages read; not theirs (or unknown) is 4806. */
    static Map<String, Object> markInboxRead(Map<String, Object> params,
            io.tesseraql.core.inbox.InboxStore inbox) {
        String eventId = text(params.get("eventId"), "");
        if (inbox == null
                || !inbox.markRead(tenant(params), subject(params), eventId)) {
            throw new TqlException(new TqlErrorCode(TqlDomain.ACCOUNT, 4806),
                    "No unread message '" + eventId + "' in your inbox");
        }
        return Map.of("ok", true);
    }

    /** Marks all of the caller's messages read (idempotent). */
    static Map<String, Object> markAllInboxRead(Map<String, Object> params,
            io.tesseraql.core.inbox.InboxStore inbox) {
        int marked = inbox == null
                ? 0
                : inbox.markAllRead(tenant(params), subject(params));
        return Map.of("ok", true, "marked", marked);
    }

    /** Starts (or restarts) TOTP enrollment: a fresh secret, stored unconfirmed. */
    static Map<String, Object> totpBegin(Map<String, Object> params,
            io.tesseraql.core.credential.TotpStore totp) {
        requireTotp(totp);
        totp.beginEnrollment(tenant(params), subject(params),
                io.tesseraql.security.totp.Totp.generateSecret());
        return Map.of("ok", true);
    }

    /** Confirms the pending enrollment with a valid code - nothing enforces until this. */
    static Map<String, Object> totpConfirm(Map<String, Object> params,
            io.tesseraql.core.credential.TotpStore totp) {
        requireTotp(totp);
        var enrollment = totp.enrollment(tenant(params), subject(params))
                .filter(e -> !e.confirmed());
        long step = enrollment.isEmpty()
                ? -1
                : io.tesseraql.security.totp.Totp.matchedStep(enrollment.get().secret(),
                        text(params.get("code"), ""));
        if (step < 0 || !totp.markUsedStep(tenant(params), subject(params), step)
                || !totp.confirmEnrollment(tenant(params), subject(params))) {
            throw new TqlException(INVALID_VALUE, "That code did not match - try again");
        }
        return Map.of("ok", true);
    }

    /** Disables TOTP after re-verifying the password (TQL-ACCOUNT-4804 on mismatch). */
    static Map<String, Object> totpDisable(Map<String, Object> params,
            io.tesseraql.core.credential.TotpStore totp, IdentityService identity,
            RealmConfig realm) {
        requireTotp(totp);
        if (identity == null || realm == null
                || new PasswordAuthenticator(identity).authenticate(realm,
                        text(params.get("loginId"), ""), text(params.get("current"), ""),
                        tenant(params)).isEmpty()) {
            throw new TqlException(WRONG_PASSWORD, "The current password does not match");
        }
        totp.remove(tenant(params), subject(params));
        return Map.of("ok", true);
    }

    private static void requireTotp(io.tesseraql.core.credential.TotpStore totp) {
        if (totp == null) {
            throw new TqlException(PASSWORD_UNAVAILABLE,
                    "Two-factor authentication needs the local identity realm");
        }
    }

    /**
     * Saves the caller's own out-of-office rule (roadmap Phase 52). The delegate is a login
     * id resolved to its subject when the managed realm is present (unknown -> 4802), else
     * a raw subject; self-delegation and inverted/expired windows are refused.
     */
    static Map<String, Object> saveDelegation(Map<String, Object> params,
            io.tesseraql.core.workflow.DelegationStore delegations, IdentityService identity,
            RealmConfig realm) {
        if (delegations == null) {
            throw new TqlException(PASSWORD_UNAVAILABLE,
                    "Delegation needs the managed workflow task inbox");
        }
        String delegate = text(params.get("delegate"), "");
        java.time.Instant startsAt = parseInstant(params.get("startsAt"));
        java.time.Instant endsAt = parseInstant(params.get("endsAt"));
        if (delegate.isBlank() || startsAt == null || endsAt == null
                || !startsAt.isBefore(endsAt) || !endsAt.isAfter(java.time.Instant.now())) {
            throw new TqlException(INVALID_VALUE,
                    "Delegation needs a delegate and a window that ends in the future");
        }
        String delegateSubject = delegate;
        if (identity != null && realm != null) {
            List<Map<String, Object>> users;
            try {
                users = identity.execute(realm, IdentityContracts.FIND_USER_BY_LOGIN,
                        Map.of("loginId", delegate));
            } catch (RuntimeException noIdentitySchema) {
                // The identity beans always bind, but the schema may not exist (sql realms,
                // bare apps). Without it the delegate is taken as a raw subject - the card
                // says so.
                users = null;
            }
            if (users != null) {
                if (users.isEmpty()) {
                    throw new TqlException(INVALID_VALUE,
                            "No user with login '" + delegate + "'");
                }
                delegateSubject = String.valueOf(users.get(0).get("user_id"));
            }
        }
        if (delegateSubject.equals(subject(params))) {
            throw new TqlException(INVALID_VALUE, "You cannot delegate to yourself");
        }
        delegations.put(tenant(params), subject(params), delegateSubject, startsAt, endsAt);
        return Map.of("ok", true);
    }

    /** Clears the caller's own rule (idempotent). */
    static Map<String, Object> clearDelegation(Map<String, Object> params,
            io.tesseraql.core.workflow.DelegationStore delegations) {
        if (delegations != null) {
            delegations.clear(tenant(params), subject(params));
        }
        return Map.of("ok", true);
    }

    private static java.time.Instant parseInstant(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        String raw = String.valueOf(value).trim();
        try {
            // Accept both the datetime-local form (no zone: interpreted as UTC) and ISO.
            return raw.length() == 16
                    ? java.time.LocalDateTime.parse(raw).toInstant(java.time.ZoneOffset.UTC)
                    : java.time.Instant.parse(raw);
        } catch (java.time.format.DateTimeParseException ex) {
            return null;
        }
    }

    private static String subject(Map<String, Object> params) {
        return text(params.get("subject"), "");
    }

    private static String tenant(Map<String, Object> params) {
        String tenant = text(params.get("tenantId"), "");
        return tenant.isEmpty() ? null : tenant;
    }

    private static String text(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }
}
