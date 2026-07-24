package io.tesseraql.yaml.config;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.model.SecuritySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Path-matched route security defaults (docs/route-defaults.md), declared once under
 * {@code tesseraql.security.defaults.routes} and merged into every HTTP route at manifest load
 * time, so the compiler, linter, and every other consumer see fully explicit effective values.
 *
 * <pre>{@code
 * security:
 *   defaults:
 *     routes:
 *       - match: /api/**
 *         auth: bearer
 *       - match: /**
 *         auth: browser
 *         csrf: auto
 * }</pre>
 *
 * <p>Rules are evaluated in declaration order against the route's served URL path and the
 * <em>first matching rule</em> contributes defaults — firewall-style, so the effective rule for
 * any path is decidable by reading the list top to bottom. A pattern segment {@code *} matches
 * within one path segment; {@code **} matches across segments. Route-local keys always win, and
 * a route whose effective auth is {@code public} never inherits a policy from a rule: public
 * means fully open, and the linter flags the combination instead
 * ({@code TQL-SEC-4131}).
 *
 * <p>{@code csrf: auto} resolves to required exactly when the effective auth is {@code browser}
 * and the method is state-changing (not {@code GET}) — the browser-session write case CSRF
 * protects.
 */
public final class SecurityDefaults {

    private static final TqlErrorCode INVALID = new TqlErrorCode(TqlDomain.SEC, 4132);

    /** One declaration-ordered default rule; {@code csrf} is {@code auto}, {@code true} or
     * {@code false}. */
    public record Rule(String match, String auth, String csrf, String policy, Pattern pattern) {
    }

    private final List<Rule> rules;

    private SecurityDefaults(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /** Parses {@code tesseraql.security.defaults.routes}; absent config yields no rules. */
    public static SecurityDefaults from(AppConfig config) {
        Object node = config.navigate("tesseraql.security.defaults.routes");
        if (node == null) {
            return new SecurityDefaults(List.of());
        }
        if (!(node instanceof List<?> list)) {
            throw new TqlException(INVALID,
                    "tesseraql.security.defaults.routes must be a list of rules");
        }
        List<Rule> rules = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                throw new TqlException(INVALID,
                        "Each security default rule must be a map with a 'match' key");
            }
            String match = resolved(config, map.get("match"));
            if (match == null || match.isBlank()) {
                throw new TqlException(INVALID,
                        "A security default rule is missing its 'match' pattern");
            }
            String auth = resolved(config, map.get("auth"));
            String csrf = resolved(config, map.get("csrf"));
            String policy = resolved(config, map.get("policy"));
            if (csrf != null && !csrf.equals("auto") && !csrf.equals("true")
                    && !csrf.equals("false")) {
                throw new TqlException(INVALID, "Security default rule '" + match
                        + "': csrf must be auto, true or false, not '" + csrf + "'");
            }
            if (auth == null && csrf == null && policy == null) {
                throw new TqlException(INVALID, "Security default rule '" + match
                        + "' declares nothing to default (auth, csrf or policy)");
            }
            rules.add(new Rule(match, auth, csrf, policy, compile(match)));
        }
        return new SecurityDefaults(rules);
    }

    /** Whether any rule is declared — lets callers skip per-route work entirely. */
    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /** The first declared rule matching the served URL path, if any. */
    public Optional<Rule> matchedRule(String urlPath) {
        return rules.stream().filter(rule -> rule.pattern().matcher(urlPath).matches())
                .findFirst();
    }

    /**
     * Resolves the effective security of one route: route-local keys win, the first matching
     * rule fills the rest. Returns the declared spec unchanged when no rule matches.
     */
    public SecuritySpec resolve(String httpMethod, String urlPath, SecuritySpec declared) {
        Optional<Rule> matched = matchedRule(urlPath);
        if (matched.isEmpty()) {
            return declared;
        }
        Rule rule = matched.get();
        String auth = declared != null && declared.auth() != null ? declared.auth() : rule.auth();
        // public means fully open: a rule must not quietly attach a policy that would then
        // authorize an anonymous principal (the linter flags the combination instead).
        String policy = declared != null && declared.policy() != null
                ? declared.policy()
                : "public".equals(auth) ? null : rule.policy();
        Boolean csrf = declared != null && declared.csrf() != null
                ? declared.csrf()
                : defaultCsrf(rule.csrf(), auth, httpMethod);
        String provider = declared == null ? null : declared.provider();
        if (auth == null && policy == null && csrf == null && provider == null) {
            return declared;
        }
        return new SecuritySpec(auth, policy, provider, csrf);
    }

    private static Boolean defaultCsrf(String csrf, String effectiveAuth, String httpMethod) {
        if (csrf == null) {
            return null;
        }
        return switch (csrf) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> "browser".equals(effectiveAuth) && !"GET".equals(httpMethod)
                    ? Boolean.TRUE
                    : null;
        };
    }

    private static String resolved(AppConfig config, Object value) {
        return value == null ? null : config.resolve(String.valueOf(value));
    }

    /**
     * Compiles the ant-style pattern with explicit, filesystem-independent semantics over the
     * served URL path: {@code **} crosses {@code /}, {@code *} stays within one segment, every
     * other character is literal.
     */
    private static Pattern compile(String match) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < match.length(); i++) {
            char c = match.charAt(i);
            // A trailing "/**" also matches the bare prefix itself ("/api/**" matches "/api"),
            // the ant-path convention authors expect.
            if (c == '/' && match.regionMatches(i, "/**", 0, 3) && i + 3 == match.length()) {
                regex.append("(?:/.*)?");
                break;
            }
            if (c == '*') {
                if (i + 1 < match.length() && match.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
