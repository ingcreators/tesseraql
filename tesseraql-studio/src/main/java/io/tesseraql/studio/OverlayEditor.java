package io.tesseraql.studio;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.SimpleYamlParser;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.flags.FlagsSpec;
import io.tesseraql.yaml.i18n.MessageCatalog;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.menu.MenuSpec;
import io.tesseraql.yaml.menu.MenuSpec.MenuItem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Studio's curated write surface over the app's configuration files (Track J2 and friends): the
 * config/flags/messages/policies/menu/connector editors all land their edits here, and the
 * overlay-writing ones funnel through {@link #writeOverlaySection} — one canonical re-serialization
 * of {@code config/overlay.yml} with one audit stamp. The read models those screens pair with
 * (connectors, effective config, editable settings) live here too, resolved from a FRESH merged
 * config so a just-saved write shows without a Studio reload.
 *
 * <p>The app home is read through a supplier because {@link StudioService#reload()} reassigns it;
 * capturing the value would pin the editor to a stale manifest. Path resolution goes through the
 * shared confinement guard ({@link DraftStore#resolve}).
 */
final class OverlayEditor {

    /** Confined app-home path resolution — {@link DraftStore#resolve}, the traversal guard. */
    @FunctionalInterface
    interface PathResolver {
        Path resolve(String relativePath);
    }

    static final TqlErrorCode MENU = new TqlErrorCode(TqlDomain.STUDIO, 4225);
    static final TqlErrorCode POLICY = new TqlErrorCode(TqlDomain.STUDIO, 4226);
    static final TqlErrorCode MESSAGE = new TqlErrorCode(TqlDomain.STUDIO, 4227);
    static final TqlErrorCode CONFIG = new TqlErrorCode(TqlDomain.STUDIO, 4228);
    static final TqlErrorCode FLAG = new TqlErrorCode(TqlDomain.STUDIO, 4229);
    /** TQL-STUDIO-4241: the menu index names no item, so the edit cannot be applied. */
    static final TqlErrorCode UNKNOWN_MENU_INDEX = new TqlErrorCode(TqlDomain.STUDIO, 4241);

    private static final Pattern EGRESS_HOST = Pattern
            .compile("(\\*\\.)?[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?");
    private static final Pattern CONNECTOR_NAME = Pattern.compile("[a-z][a-z0-9-]*");
    private static final Pattern POLICY_ID = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern LOCALE_TAG = Pattern.compile("[A-Za-z0-9-]+");
    private static final Pattern MESSAGE_KEY = Pattern.compile("[A-Za-z0-9._-]+");

    private final SimpleYamlParser parser = new SimpleYamlParser();
    private final Supplier<Path> appHome;
    private final boolean readOnly;
    private final PathResolver paths;
    private final StudioService.AuditRecorder audit;

    OverlayEditor(Supplier<Path> appHome, boolean readOnly, PathResolver paths,
            StudioService.AuditRecorder audit) {
        this.appHome = appHome;
        this.readOnly = readOnly;
        this.paths = paths;
        this.audit = audit;
    }

    private Path appHome() {
        return appHome.get();
    }

    private Path resolve(String relativePath) {
        return paths.resolve(relativePath);
    }

    /**
     * Sets one dotted-path key in {@code config/overlay.yml} (Track J2 write-through), other keys
     * preserved; a {@code null} value removes the leaf. Restart-bound settings stay honest at the
     * call site — this method only makes the write durable and audited.
     */
    void setOverlayPath(String dottedKey, Object value, String action, String actor) {
        writeOverlaySection(Map.of(dottedKey, value == null ? StudioService.REMOVE : value),
                action, actor);
    }

    /**
     * Applies several dotted-path writes to {@code config/overlay.yml} in one save (Track J2):
     * the wizard write-through and connector editors compose their sections from this. Values are
     * scalars, lists, or maps; {@link StudioService#REMOVE} deletes the leaf.
     *
     * <p><strong>The whole file is re-serialized canonically.</strong> A single-key edit round-trips
     * {@code config/overlay.yml} through the parser to a plain map and back, so every comment and
     * all hand formatting in it are lost — including in sections the caller never touched. This is
     * the same trade {@code routeFormSave} makes, and it says so in its javadoc and on its screen;
     * this one silently did not (docs/silent-tolerance.md T9). The overlay-writing screens carry
     * the note now.
     */
    void writeOverlaySection(Map<String, Object> values, String action, String actor) {
        if (readOnly) {
            throw new TqlException(StudioService.READ_ONLY,
                    "Studio is read-only; editing config is disabled");
        }
        Path overlay = resolve("config/overlay.yml");
        Map<String, Object> tree = Files.isRegularFile(overlay)
                ? StudioService.mutableCopy(parser.parseTree(overlay))
                : new LinkedHashMap<>();
        values.forEach((dottedKey, value) -> {
            String[] segments = dottedKey.split("\\.");
            Map<String, Object> parent = tree;
            for (int i = 0; i < segments.length - 1; i++) {
                parent = StudioService.childMap(parent, segments[i]);
            }
            if (value == StudioService.REMOVE) {
                parent.remove(segments[segments.length - 1]);
            } else {
                parent.put(segments[segments.length - 1], value);
            }
        });
        try {
            Files.createDirectories(overlay.getParent());
            Files.writeString(overlay, parser.write(tree));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        audit.record(actor, action, "config/overlay.yml");
    }

    /** The effective (merged, overlay-included) string list at {@code dottedKey}; empty when absent. */
    List<String> effectiveStringList(String dottedKey) {
        // A fresh load so a just-written overlay value is visible without a full Studio reload.
        Object value = new ManifestLoader().load(appHome()).config().navigate(dottedKey);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    /**
     * Adds or removes one egress allow-list host (Track J2). Deep-merge replaces lists, so the
     * overlay carries the FULL effective list after the change — base-config hosts included.
     * The caller gates this behind the confirm dialog (egress changes widen the app's reach).
     */
    void updateEgressHosts(String scope, String host, boolean remove, String actor) {
        String key = egressKey(scope);
        String clean = StudioService.trimToNull(host);
        if (clean == null || !EGRESS_HOST.matcher(clean).matches()) {
            throw new TqlException(StudioService.CONNECTORS,
                    "An egress host is a hostname or *.wildcard: " + host);
        }
        List<String> full = new ArrayList<>(effectiveStringList(key));
        if (remove) {
            full.remove(clean);
        } else if (!full.contains(clean)) {
            full.add(clean);
        }
        writeOverlaySection(Map.of(key, full), "egress", actor);
    }

    private static String egressKey(String scope) {
        return switch (String.valueOf(scope)) {
            case "outbound" -> "tesseraql.http.outbound.allowedHosts";
            case "poll" -> "tesseraql.connectors.poll.allowedHosts";
            default -> throw new TqlException(StudioService.CONNECTORS,
                    "Unknown egress scope (outbound|poll): " + scope);
        };
    }

    /**
     * Adds or replaces an inbound-webhook verifier (Track J2): the HMAC secret is a validated
     * secret <i>reference</i>, never a value. Applies on the next start (verifiers load at boot).
     */
    void writeWebhookVerifier(String name, String secretRef, String signatureHeader,
            String timestampHeader, String idHeader, String tolerance, String actor) {
        String clean = requireConnectorName(name);
        Map<String, Object> verifier = new LinkedHashMap<>();
        verifier.put("secret",
                StudioService.requireSecretReference("The webhook secret", secretRef));
        StudioService.putOrRemove(verifier, "signatureHeader",
                StudioService.trimToNull(signatureHeader));
        StudioService.putOrRemove(verifier, "timestampHeader",
                StudioService.trimToNull(timestampHeader));
        StudioService.putOrRemove(verifier, "idHeader", StudioService.trimToNull(idHeader));
        String cleanTolerance = StudioService.trimToNull(tolerance);
        if (cleanTolerance != null) {
            try {
                java.time.Duration.parse(cleanTolerance);
            } catch (java.time.format.DateTimeParseException ex) {
                throw new TqlException(StudioService.CONNECTORS,
                        "tolerance must be an ISO-8601 duration like PT5M: " + tolerance);
            }
            verifier.put("tolerance", cleanTolerance);
        }
        writeOverlaySection(Map.of("tesseraql.connectors.webhooks." + clean, verifier),
                "connectors", actor);
    }

    /**
     * Adds or replaces an outbound/poll connector credential (Track J2). Secret-carrying fields
     * (bearer token, basic password, header value) must be secret references; the username and
     * header name ride plain. Applies on the next start.
     */
    void writeConnectorCredential(String scope, String name, String type, String token,
            String username, String password, String header, String value, String actor) {
        String prefix = switch (String.valueOf(scope)) {
            case "outbound" -> "tesseraql.http.outbound.credentials.";
            case "poll" -> "tesseraql.connectors.poll.credentials.";
            default -> throw new TqlException(StudioService.CONNECTORS,
                    "Unknown credential scope (outbound|poll): " + scope);
        };
        String clean = requireConnectorName(name);
        Map<String, Object> credential = new LinkedHashMap<>();
        switch (String.valueOf(type)) {
            case "bearer" -> {
                credential.put("type", "bearer");
                credential.put("token",
                        StudioService.requireSecretReference("The bearer token", token));
            }
            case "basic" -> {
                credential.put("type", "basic");
                String user = StudioService.trimToNull(username);
                if (user == null) {
                    throw new TqlException(StudioService.CONNECTORS,
                            "A basic credential needs a username");
                }
                credential.put("username", user);
                credential.put("password",
                        StudioService.requireSecretReference("The password", password));
            }
            case "header" -> {
                credential.put("type", "header");
                String headerName = StudioService.trimToNull(header);
                if (headerName == null) {
                    throw new TqlException(StudioService.CONNECTORS,
                            "A header credential needs a header name");
                }
                credential.put("header", headerName);
                credential.put("value",
                        StudioService.requireSecretReference("The header value", value));
            }
            default -> throw new TqlException(StudioService.CONNECTORS,
                    "Unknown credential type (bearer|basic|header): " + type);
        }
        writeOverlaySection(Map.of(prefix + clean, credential), "connectors", actor);
    }

    private static String requireConnectorName(String name) {
        String clean = StudioService.trimToNull(name);
        if (clean == null || !CONNECTOR_NAME.matcher(clean).matches()) {
            throw new TqlException(StudioService.CONNECTORS,
                    "A connector name is lowercase letters, digits and dashes: " + name);
        }
        return clean;
    }

    /**
     * The connectors read model (Track J2): egress allow-lists, outbound/poll credentials and
     * webhook verifiers from the FRESH merged config (a just-saved overlay write shows without a
     * Studio reload). Secret-ish values are shown as their reference with any literal fallback
     * elided; a non-reference literal is never echoed.
     */
    Map<String, Object> connectorsView() {
        var config = new ManifestLoader().load(appHome()).config();
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("outboundHosts", stringList(config.navigate(
                "tesseraql.http.outbound.allowedHosts")));
        model.put("pollHosts", stringList(config.navigate(
                "tesseraql.connectors.poll.allowedHosts")));
        model.put("outboundCredentials", credentialRows(config.navigate(
                "tesseraql.http.outbound.credentials")));
        model.put("pollCredentials", credentialRows(config.navigate(
                "tesseraql.connectors.poll.credentials")));
        model.put("webhooks", webhookRows(config.navigate("tesseraql.connectors.webhooks")));
        return model;
    }

    private static List<String> stringList(Object value) {
        return value instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
    }

    private static List<Map<String, Object>> credentialRows(Object value) {
        List<Map<String, Object>> rows = new ArrayList<>();
        StudioService.anyMap(value).forEach((name, spec) -> {
            Map<String, Object> credential = StudioService.anyMap(spec);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("type", StudioService.scalar(credential.get("type")));
            row.put("target", credential.get("header") != null
                    ? StudioService.scalar(credential.get("header"))
                    : StudioService.scalar(credential.get("username")));
            Object secret = credential.get("token") != null
                    ? credential.get("token")
                    : credential.get("password") != null
                            ? credential.get("password")
                            : credential.get("value");
            row.put("secret", redactedReference(StudioService.scalar(secret)));
            rows.add(row);
        });
        return rows;
    }

    private static List<Map<String, Object>> webhookRows(Object value) {
        List<Map<String, Object>> rows = new ArrayList<>();
        StudioService.anyMap(value).forEach((name, spec) -> {
            Map<String, Object> verifier = StudioService.anyMap(spec);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("secret", redactedReference(StudioService.scalar(verifier.get("secret"))));
            row.put("signatureHeader", StudioService.scalar(verifier.get("signatureHeader")));
            row.put("timestampHeader", StudioService.scalar(verifier.get("timestampHeader")));
            row.put("idHeader", StudioService.scalar(verifier.get("idHeader")));
            row.put("tolerance", StudioService.scalar(verifier.get("tolerance")));
            rows.add(row);
        });
        return rows;
    }

    /**
     * A displayable form of a secret-carrying config value: a pure reference shows as-is, a
     * reference with a literal fallback elides the fallback, anything else is masked entirely.
     */
    static String redactedReference(String value) {
        if (value == null) {
            return null;
        }
        if (StudioService.isSecretReference(value)) {
            return value;
        }
        java.util.regex.Matcher withDefault = java.util.regex.Pattern
                .compile("\\$\\{(secret\\.[A-Za-z0-9_.-]+):.*\\}")
                .matcher(value.trim());
        if (withDefault.matches()) {
            return "${" + withDefault.group(1) + ":\u2026}";
        }
        return "\u2022\u2022\u2022";
    }

    /** The app's message-catalog locale tags ({@code messages/<tag>.yml}), tag-sorted. */
    List<String> messageLocales() {
        return new ArrayList<>(MessageCatalog.load(appHome().resolve("messages")).tags());
    }

    /** Each locale's flat key→value message entries (dotted keys), for the i18n editor table. */
    Map<String, Map<String, String>> messageCatalogs() {
        MessageCatalog catalog = MessageCatalog.load(appHome().resolve("messages"));
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (String tag : catalog.tags()) {
            out.put(tag, catalog.entries(tag));
        }
        return out;
    }

    /**
     * Upserts a translation into {@code messages/<locale>.yml} — the dotted {@code key} is written
     * into the nested map, other keys preserved, creating the file/locale if new. Edit-gated and
     * audited; the message resolver reads the catalog live, so the change is served immediately.
     */
    void setMessage(String locale, String key, String value, String actor) {
        String tag = requireLocaleTag(locale);
        String messageKey = requireMessageKey(key);
        if (readOnly) {
            throw new TqlException(StudioService.READ_ONLY,
                    "Studio is read-only; editing messages is disabled");
        }
        Path file = resolve("messages/" + tag + ".yml");
        Map<String, Object> tree = Files.isRegularFile(file)
                ? StudioService.mutableCopy(parser.parseTree(file))
                : new LinkedHashMap<>();
        String[] segments = messageKey.split("\\.");
        Map<String, Object> node = tree;
        for (int i = 0; i < segments.length - 1; i++) {
            node = StudioService.childMap(node, segments[i]);
        }
        node.put(segments[segments.length - 1], value == null ? "" : value);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, parser.write(tree));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        audit.record(actor, "message", "messages/" + tag + ".yml");
    }

    /**
     * The app's effective (merged) configuration flattened to dotted-key rows for the Studio config
     * viewer, sorted by key. Values are shown unresolved (so {@code ${ENV}} references stay visible,
     * not their secret values); a value whose key names a secret is redacted unless it is such a
     * reference.
     */
    List<Map<String, Object>> effectiveConfig() {
        Map<String, Object> root = new ManifestLoader().load(appHome()).config().root();
        java.util.TreeMap<String, Object> flat = new java.util.TreeMap<>();
        flattenConfig("", root, flat);
        List<Map<String, Object>> rows = new ArrayList<>();
        flat.forEach((key, value) -> {
            String rendered = value == null ? "" : String.valueOf(value);
            boolean secret = isSecretKey(key) && !rendered.startsWith("${");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("value", secret ? "••••••" : rendered);
            row.put("secret", secret);
            rows.add(row);
        });
        return rows;
    }

    private static void flattenConfig(String prefix, Object node, Map<String, Object> out) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((k, v) -> flattenConfig(
                    prefix.isEmpty() ? String.valueOf(k) : prefix + "." + k, v, out));
        } else if (node instanceof List<?> list) {
            out.put(prefix, list.toString());
        } else {
            out.put(prefix, node);
        }
    }

    /** One editable configuration setting: its dotted key, label, input type, and help text. */
    record ConfigSetting(String key, String label, String type, String help) {
    }

    /**
     * The curated set of settings the Studio config editor may change. Deliberately limited to safe,
     * scalar, restart-to-apply keys — engine-critical sections (datasources, camel, security auth)
     * are never editable here.
     */
    private static final List<ConfigSetting> EDITABLE_SETTINGS = List.of(
            new ConfigSetting("tesseraql.app.name", "App name", "string",
                    "Shown in the app chrome."),
            new ConfigSetting("tesseraql.i18n.defaultLocale", "Default locale", "string",
                    "BCP-47 tag, e.g. en."),
            new ConfigSetting("tesseraql.outbox.dispatch.fixedDelay", "Outbox dispatch delay",
                    "string", "e.g. 5s; empty disables the dispatcher."),
            new ConfigSetting("tesseraql.outbox.dispatch.maxAttempts", "Outbox max attempts",
                    "integer", "Delivery attempts before an outbox row is parked."),
            new ConfigSetting("tesseraql.retention.sweep", "Retention sweep interval", "string",
                    "e.g. 1h; empty disables retention."),
            new ConfigSetting("tesseraql.retention.outbox", "Outbox retention", "string",
                    "e.g. 30d."),
            new ConfigSetting("tesseraql.retention.jobs", "Job retention", "string", "e.g. 90d."));

    /** The curated editable settings with their current effective values, for the config editor. */
    List<Map<String, Object>> editableSettings() {
        AppConfig config = new ManifestLoader().load(appHome()).config();
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConfigSetting setting : EDITABLE_SETTINGS) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", setting.key());
            row.put("label", setting.label());
            row.put("type", setting.type());
            row.put("help", setting.help());
            row.put("value", config.getString(setting.key()).orElse(""));
            out.add(row);
        }
        return out;
    }

    /**
     * Overrides a curated setting in {@code config/overlay.yml} (the base config untouched), or, when
     * {@code value} is blank, removes the override. Only whitelisted keys are accepted. Edit-gated and
     * audited; applied on the next restart (the setting is read at startup).
     */
    void setConfigValue(String key, String value, String actor) {
        ConfigSetting setting = EDITABLE_SETTINGS.stream().filter(s -> s.key().equals(key))
                .findFirst().orElseThrow(() -> new TqlException(CONFIG,
                        "Not an editable setting: " + key));
        if (readOnly) {
            throw new TqlException(StudioService.READ_ONLY,
                    "Studio is read-only; editing config is disabled");
        }
        String trimmed = value == null ? "" : value.strip();
        if ("integer".equals(setting.type()) && !trimmed.isEmpty()) {
            try {
                Long.parseLong(trimmed);
            } catch (NumberFormatException ex) {
                throw new TqlException(CONFIG,
                        setting.label() + " must be a whole number");
            }
        }
        Path overlay = resolve("config/overlay.yml");
        Map<String, Object> tree = Files.isRegularFile(overlay)
                ? StudioService.mutableCopy(parser.parseTree(overlay))
                : new LinkedHashMap<>();
        String[] segments = key.split("\\.");
        if (trimmed.isEmpty()) {
            removePath(tree, segments, 0);
        } else {
            Map<String, Object> node = tree;
            for (int i = 0; i < segments.length - 1; i++) {
                node = StudioService.childMap(node, segments[i]);
            }
            node.put(segments[segments.length - 1], trimmed);
        }
        try {
            Files.createDirectories(overlay.getParent());
            Files.writeString(overlay, parser.write(tree));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        audit.record(actor, "config", "config/overlay.yml");
    }

    /** Removes the leaf at the dotted path from {@code node}, pruning any emptied ancestor maps. */
    @SuppressWarnings("unchecked")
    private static void removePath(Map<String, Object> node, String[] segments, int index) {
        if (index == segments.length - 1) {
            node.remove(segments[index]);
            return;
        }
        if (node.get(segments[index]) instanceof Map<?, ?> child) {
            Map<String, Object> childMap = (Map<String, Object>) child;
            removePath(childMap, segments, index + 1);
            if (childMap.isEmpty()) {
                node.remove(segments[index]);
            }
        }
    }

    /** Whether a dotted config key names a secret whose literal value should be redacted. */
    private static boolean isSecretKey(String key) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("password") || lower.contains("passphrase")
                || lower.contains("secret") || lower.contains("token")
                || lower.contains("credential") || lower.contains("apikey")
                || lower.contains("privatekey");
    }

    /** The app's live feature flags ({@code config/flags.yml}) — name to (typed) value. */
    Map<String, Object> flags() {
        return FlagsSpec.load(appHome()).values();
    }

    /**
     * Sets (or adds) a feature flag in {@code config/flags.yml}, coercing the value by {@code type}
     * ({@code boolean}/{@code number}/{@code string}). Edit-gated and audited; served live (the
     * request binder reads flags live), so the change takes effect on the next request.
     */
    void setFlag(String name, String value, String type, String actor) {
        String key = requireFlagName(name);
        Object typed = coerceFlag(type, value);
        Map<String, Object> values = new LinkedHashMap<>(FlagsSpec.load(appHome()).values());
        values.put(key, typed);
        writeFlags(values, actor);
    }

    /** Removes a feature flag; a no-op when it is not set. */
    void removeFlag(String name, String actor) {
        Map<String, Object> values = new LinkedHashMap<>(FlagsSpec.load(appHome()).values());
        if (values.remove(name) != null) {
            writeFlags(values, actor);
        }
    }

    private void writeFlags(Map<String, Object> values, String actor) {
        if (readOnly) {
            throw new TqlException(StudioService.READ_ONLY,
                    "Studio is read-only; editing flags is disabled");
        }
        Path file = resolve("config/flags.yml");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, FlagsSpec.toYaml(values));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        audit.record(actor, "flag", "config/flags.yml");
    }

    private static String requireFlagName(String name) {
        String trimmed = StudioService.trimToNull(name);
        if (trimmed == null || !POLICY_ID.matcher(trimmed).matches()) {
            throw new TqlException(FLAG, "Invalid flag name: " + name);
        }
        return trimmed;
    }

    private static Object coerceFlag(String type, String value) {
        String raw = value == null ? "" : value.strip();
        return switch (type == null ? "string" : type) {
            case "boolean" -> Boolean.parseBoolean(raw);
            case "number" -> {
                try {
                    yield raw.contains(".") ? Double.parseDouble(raw) : Long.parseLong(raw);
                } catch (NumberFormatException ex) {
                    throw new TqlException(FLAG,
                            "Flag value must be a number: " + value);
                }
            }
            default -> raw;
        };
    }

    private static String requireLocaleTag(String locale) {
        String trimmed = StudioService.trimToNull(locale);
        if (trimmed == null || !LOCALE_TAG.matcher(trimmed).matches()) {
            throw new TqlException(MESSAGE, "Invalid locale tag: " + locale);
        }
        return trimmed;
    }

    private static String requireMessageKey(String key) {
        String trimmed = StudioService.trimToNull(key);
        if (trimmed == null || !MESSAGE_KEY.matcher(trimmed).matches()
                || trimmed.startsWith(".") || trimmed.endsWith(".")) {
            throw new TqlException(MESSAGE, "Invalid message key: " + key);
        }
        return trimmed;
    }

    /**
     * Grants a policy an extra {@code role} or {@code permission} rule by writing the policy's full
     * rule set to {@code config/overlay.yml} (the last-merged overlay, so the base config is left
     * intact). A previously undefined policy is created. Edit-gated and audited; the caller reloads
     * the security engine so the change is live.
     */
    void addPolicyRule(String policyId, String kind, String value, String actor) {
        String id = requirePolicyId(policyId);
        String ruleKind = requireRuleKind(kind);
        String ruleValue = StudioService.trimToNull(value);
        if (ruleValue == null) {
            throw new TqlException(POLICY,
                    "A policy rule needs a " + ruleKind + " value");
        }
        List<Map<String, Object>> rules = effectivePolicyRules(id);
        boolean present = rules.stream()
                .anyMatch(r -> ruleValue.equals(String.valueOf(r.get(ruleKind))));
        if (!present) {
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put(ruleKind, ruleValue);
            rules.add(rule);
            writeOverlayPolicy(id, rules, actor);
        }
    }

    /**
     * Revokes a {@code role}/{@code permission} rule from a policy by writing the reduced rule set to
     * {@code config/overlay.yml} (which overrides the base). Removing the last rule leaves a policy
     * that grants no one (deny-by-default). A base-only policy cannot be deleted via the overlay.
     */
    void removePolicyRule(String policyId, String kind, String value, String actor) {
        String id = requirePolicyId(policyId);
        String ruleKind = requireRuleKind(kind);
        String ruleValue = StudioService.trimToNull(value);
        List<Map<String, Object>> rules = effectivePolicyRules(id);
        boolean removed = rules.removeIf(
                r -> ruleValue != null && ruleValue.equals(String.valueOf(r.get(ruleKind))));
        if (removed) {
            writeOverlayPolicy(id, rules, actor);
        }
    }

    /** The effective {@code anyOf} rule maps of a policy from the current merged config. */
    private List<Map<String, Object>> effectivePolicyRules(String policyId) {
        Object policies = new ManifestLoader().load(appHome()).config()
                .navigate("tesseraql.security.policies");
        List<Map<String, Object>> out = new ArrayList<>();
        if (policies instanceof Map<?, ?> byId && byId.get(policyId) instanceof Map<?, ?> spec
                && spec.get("anyOf") instanceof List<?> rules) {
            for (Object rule : rules) {
                if (rule instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    map.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    out.add(copy);
                }
            }
        }
        return out;
    }

    /** Writes {@code tesseraql.security.policies.<id>.anyOf} into overlay.yml, other keys preserved. */
    private void writeOverlayPolicy(String policyId, List<Map<String, Object>> rules,
            String actor) {
        if (readOnly) {
            throw new TqlException(StudioService.READ_ONLY,
                    "Studio is read-only; editing policies is disabled");
        }
        Path overlay = resolve("config/overlay.yml");
        Map<String, Object> tree = Files.isRegularFile(overlay)
                ? StudioService.mutableCopy(parser.parseTree(overlay))
                : new LinkedHashMap<>();
        Map<String, Object> policies = StudioService.childMap(StudioService.childMap(
                StudioService.childMap(tree, "tesseraql"), "security"), "policies");
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("anyOf", rules);
        policies.put(policyId, policy);
        try {
            Files.createDirectories(overlay.getParent());
            Files.writeString(overlay, parser.write(tree));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        audit.record(actor, "policy", "config/overlay.yml");
    }

    private static String requirePolicyId(String id) {
        String trimmed = StudioService.trimToNull(id);
        if (trimmed == null || !POLICY_ID.matcher(trimmed).matches()) {
            throw new TqlException(POLICY, "Invalid policy id: " + id);
        }
        return trimmed;
    }

    private static String requireRuleKind(String kind) {
        String trimmed = StudioService.trimToNull(kind);
        if (!"role".equals(trimmed) && !"permission".equals(trimmed)) {
            throw new TqlException(POLICY,
                    "A policy rule kind must be 'role' or 'permission'");
        }
        return trimmed;
    }

    /** The app's current declarative sidebar menu items ({@code config/menu.yml}); empty if none. */
    List<MenuItem> menuItems() {
        return MenuSpec.load(appHome()).items();
    }

    /**
     * Appends a menu item to {@code config/menu.yml} and records it to the audit trail. {@code label}
     * and {@code href} are required; {@code icon} is an optional sprite id; {@code rolesCsv}/
     * {@code permsCsv} are comma-separated visibility lists (empty ⇒ a public item).
     */
    void addMenuItem(String label, String href, String icon, String rolesCsv,
            String permsCsv, String actor) {
        String cleanLabel = StudioService.trimToNull(label);
        String cleanHref = StudioService.trimToNull(href);
        if (cleanLabel == null || cleanHref == null) {
            throw new TqlException(MENU, "A menu item needs a label and an href");
        }
        List<MenuItem> items = new ArrayList<>(menuItems());
        items.add(new MenuItem(cleanLabel, cleanHref, StudioService.trimToNull(icon),
                StudioService.csv(rolesCsv), StudioService.csv(permsCsv)));
        writeMenu(items, actor);
    }

    /**
     * Removes the menu item at {@code index} and records the change.
     *
     * <p>An index outside the list is refused rather than ignored: the handler answered
     * {@code {"removed": true}} for it, so a malformed or stale index reported a change that
     * never happened and left no audit record to contradict it (docs/silent-tolerance.md O10).
     */
    void removeMenuItem(int index, String actor) {
        List<MenuItem> items = new ArrayList<>(menuItems());
        requireIndex(index, items.size());
        items.remove(index);
        writeMenu(items, actor);
    }

    /**
     * Moves the menu item at {@code index} one slot up ({@code delta < 0}) or down
     * ({@code delta > 0}). Moving the first item up, or the last down, is a legitimate no-op —
     * the item is already where it was asked to go — but an index outside the list is refused.
     */
    void moveMenuItem(int index, int delta, String actor) {
        List<MenuItem> items = new ArrayList<>(menuItems());
        requireIndex(index, items.size());
        int target = index + Integer.signum(delta);
        if (target < 0 || target >= items.size()) {
            return;
        }
        items.add(target, items.remove(index));
        writeMenu(items, actor);
    }

    private static void requireIndex(int index, int size) {
        if (index < 0 || index >= size) {
            throw new TqlException(UNKNOWN_MENU_INDEX,
                    "Menu index " + index + " names no item; the menu has " + size + " item"
                            + (size == 1 ? "" : "s"));
        }
    }

    /** Serializes the menu back to {@code config/menu.yml} (edit-gated) and records the change. */
    private void writeMenu(List<MenuItem> items, String actor) {
        if (readOnly) {
            throw new TqlException(StudioService.READ_ONLY,
                    "Studio is read-only; editing the menu is disabled");
        }
        Path target = resolve("config/menu.yml");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, MenuSpec.toYaml(items));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        audit.record(actor, "menu", "config/menu.yml");
    }
}
