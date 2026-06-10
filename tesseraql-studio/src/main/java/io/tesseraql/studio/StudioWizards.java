package io.tesseraql.studio;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.List;
import java.util.Map;

/**
 * Generates TesseraQL configuration snippets for the Studio setup wizards (design ch. 16): SAML SP,
 * SCIM provisioning and identity realm mapping. Each wizard collects typed fields and emits a YAML
 * fragment (rooted at {@code tesseraql:}) that can be reviewed and saved as a draft.
 *
 * <p>Generation is pure and dependency-free. Secrets are never emitted literally: the SCIM outbound
 * token is rendered as a {@code ${ENV}} placeholder so generated config can be committed safely.
 */
public final class StudioWizards {

    private static final TqlErrorCode UNKNOWN = new TqlErrorCode(TqlDomain.STUDIO, 4004);
    private static final TqlErrorCode MISSING = new TqlErrorCode(TqlDomain.STUDIO, 4002);

    private StudioWizards() {
    }

    /** A single input field of a wizard form. */
    public record WizardField(String name, String label, boolean required, String placeholder) {
    }

    /** A wizard: its kind id, display title and input fields. */
    public record Wizard(String kind, String title, List<WizardField> fields) {
    }

    /** The available wizards, in display order. */
    public static List<Wizard> all() {
        return List.of(
                new Wizard("saml", "SAML SP", List.of(
                        new WizardField("spAudience", "SP entity ID / audience", true, "https://app.example.com/saml"),
                        new WizardField("acsUrl", "Assertion Consumer Service URL", true, "https://app.example.com/_tesseraql/saml/acs"),
                        new WizardField("ssoUrl", "IdP SSO URL", true, "https://idp.example.com/sso"),
                        new WizardField("sloUrl", "IdP SLO URL (optional)", false, "https://idp.example.com/slo"),
                        new WizardField("publicKey", "IdP signing certificate, PEM (optional)", false, "-----BEGIN CERTIFICATE-----"),
                        new WizardField("loginIdAttribute", "Login ID attribute", true, "urn:oid:0.9.2342.19200300.100.1.1"),
                        new WizardField("emailAttribute", "Email attribute (optional)", false, "urn:oid:0.9.2342.19200300.100.1.3"),
                        new WizardField("nameIdFormat", "NameID format (optional)", false, "urn:oasis:names:tc:SAML:2.0:nameid-format:persistent"),
                        new WizardField("provision", "Auto-provision new users (true/false)", false, "true"))),
                new Wizard("scim", "SCIM provisioning", List.of(
                        new WizardField("groups", "Enable group provisioning (true/false)", false, "true"),
                        new WizardField("outbound", "Enable outbound provisioning (true/false)", false, "false"),
                        new WizardField("outboundUrl", "Outbound target URL (optional)", false, "https://idp.example.com/scim/v2"),
                        new WizardField("outboundTokenEnv", "Outbound token env var (optional)", false, "SCIM_OUTBOUND_TOKEN"))),
                new Wizard("identity", "Identity realm mapping", List.of(
                        new WizardField("realmId", "Realm id", true, "local"),
                        new WizardField("type", "Realm type (managed/sql)", true, "managed"),
                        new WizardField("datasource", "Datasource name", true, "main"),
                        new WizardField("sqlRoot", "SQL contract root, sql realms (optional)", false, "security/identity/local"),
                        new WizardField("userManagement", "User management (readOnly/readWrite)", false, "readWrite"))));
    }

    /** The wizard for {@code kind}, or a {@link TqlException} when unknown. */
    public static Wizard byKind(String kind) {
        return all().stream().filter(w -> w.kind().equals(kind)).findFirst()
                .orElseThrow(() -> new TqlException(UNKNOWN, "Unknown wizard: " + kind));
    }

    /** Generates the YAML config fragment for {@code kind} from the submitted {@code inputs}. */
    public static String generate(String kind, Map<String, String> inputs) {
        return switch (kind) {
            case "saml" -> saml(inputs);
            case "scim" -> scim(inputs);
            case "identity" -> identity(inputs);
            default -> throw new TqlException(UNKNOWN, "Unknown wizard: " + kind);
        };
    }

    private static String saml(Map<String, String> in) {
        Yaml y = new Yaml();
        y.line(0, "tesseraql:");
        y.line(1, "saml:");
        y.kv(2, "enabled", "true", false);
        y.line(2, "sp:");
        y.kv(3, "audience", require(in, "spAudience"), true);
        y.kv(3, "acsUrl", require(in, "acsUrl"), true);
        y.optional(3, "nameIdFormat", in.get("nameIdFormat"));
        y.line(2, "idp:");
        y.kv(3, "ssoUrl", require(in, "ssoUrl"), true);
        y.optional(3, "sloUrl", in.get("sloUrl"));
        y.optional(3, "publicKey", in.get("publicKey"));
        y.line(2, "attributes:");
        y.kv(3, "loginId", require(in, "loginIdAttribute"), true);
        y.optional(3, "email", in.get("emailAttribute"));
        y.line(2, "link:");
        y.kv(3, "enabled", "true", false);
        y.kv(3, "provision", bool(in.get("provision"), false), false);
        return y.toString();
    }

    private static String scim(Map<String, String> in) {
        Yaml y = new Yaml();
        y.line(0, "tesseraql:");
        y.line(1, "scim:");
        y.kv(2, "enabled", "true", false);
        y.line(2, "groups:");
        y.kv(3, "enabled", bool(in.get("groups"), false), false);
        boolean outbound = "true".equalsIgnoreCase(in.get("outbound"));
        y.line(2, "outbound:");
        y.kv(3, "enabled", String.valueOf(outbound), false);
        if (outbound) {
            y.line(3, "target:");
            y.optional(4, "url", in.get("outboundUrl"));
            // Secrets are never emitted literally; reference an environment variable instead.
            String env = blankToNull(in.get("outboundTokenEnv"));
            y.kv(4, "token", "${" + (env == null ? "SCIM_OUTBOUND_TOKEN" : env) + "}", true);
        }
        return y.toString();
    }

    private static String identity(Map<String, String> in) {
        String realmId = require(in, "realmId");
        Yaml y = new Yaml();
        y.line(0, "tesseraql:");
        y.line(1, "identity:");
        y.kv(2, "defaultRealm", realmId, true);
        y.line(2, "realms:");
        y.line(3, realmId + ":");
        y.kv(4, "type", require(in, "type"), true);
        y.kv(4, "datasource", require(in, "datasource"), true);
        y.optional(4, "sqlRoot", in.get("sqlRoot"));
        String userMgmt = blankToNull(in.get("userManagement"));
        if (userMgmt != null) {
            y.line(4, "capabilities:");
            y.kv(5, "userManagement", userMgmt, true);
        }
        return y.toString();
    }

    private static String require(Map<String, String> in, String key) {
        String value = blankToNull(in.get(key));
        if (value == null) {
            throw new TqlException(MISSING, "Missing required field: " + key);
        }
        return value;
    }

    private static String bool(String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return String.valueOf(fallback);
        }
        return String.valueOf("true".equalsIgnoreCase(value.trim()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** A tiny indented YAML writer. String scalars are double-quoted and escaped. */
    private static final class Yaml {
        private final StringBuilder sb = new StringBuilder();

        void line(int indent, String text) {
            sb.append("  ".repeat(indent)).append(text).append('\n');
        }

        void kv(int indent, String key, String value, boolean quote) {
            sb.append("  ".repeat(indent)).append(key).append(": ")
                    .append(quote ? quote(value) : value).append('\n');
        }

        void optional(int indent, String key, String value) {
            if (value != null && !value.isBlank()) {
                kv(indent, key, value.trim(), true);
            }
        }

        private static String quote(String value) {
            String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "");
            return '"' + escaped + '"';
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }
}
