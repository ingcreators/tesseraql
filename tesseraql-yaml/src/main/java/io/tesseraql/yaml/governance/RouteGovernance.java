package io.tesseraql.yaml.governance;

import io.tesseraql.core.util.Hashing;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.RouteDefinition;
import io.tesseraql.yaml.model.SqlBinding;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Assesses every route's governance mode and risk (design ch. 51).
 *
 * <p>The mode is derived from what the route actually uses - it cannot be claimed:
 * <ul>
 *   <li>{@code managed} - a standard recipe under the full framework guardrails</li>
 *   <li>{@code extended} - binds a runtime service provider (Java extension surface)</li>
 *   <li>{@code advanced} - a write route without authentication (guardrails bypassed)</li>
 * </ul>
 *
 * <p>The risk score adds a point weight per factor (unauthenticated writes, missing authorization
 * policy or idempotency, undeclared request inputs, service bindings, file generation); the
 * factors are reported verbatim so a reviewer sees exactly why a route scored. Each assessment
 * carries the SHA-256 of the route source, which the approval ledger pins (design ch. 51 review
 * workflow): editing an approved route invalidates its approval.
 */
public final class RouteGovernance {

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private RouteGovernance() {
    }

    /** One route's governance assessment. */
    public record Assessment(String routeId, String source, String mode, int riskScore,
            List<String> riskFactors, String sha256) {
    }

    /** Assesses every route in the manifest. */
    public static List<Assessment> assess(AppManifest manifest) {
        List<Assessment> assessments = new ArrayList<>();
        for (RouteFile route : manifest.routes()) {
            assessments.add(assess(manifest, route));
        }
        return assessments;
    }

    /** Assesses one route. */
    public static Assessment assess(AppManifest manifest, RouteFile route) {
        RouteDefinition definition = route.definition();
        boolean write = isWrite(route);
        boolean authenticated = isAuthenticated(definition);
        boolean usesService = usesService(definition);

        List<String> factors = new ArrayList<>();
        int score = 0;
        if (write && !authenticated) {
            score += 4;
            factors.add("write route without authentication");
        }
        if (!write && !authenticated) {
            score += 1;
            factors.add("public read route");
        }
        if (write && authenticated
                && (definition.security() == null || definition.security().policy() == null)) {
            score += 1;
            factors.add("write route without an authorization policy");
        }
        if (write && "command-json".equals(definition.recipe())
                && definition.idempotency() == null) {
            score += 2;
            factors.add("write route without an idempotency declaration");
        }
        if (usesService) {
            score += 1;
            factors.add("binds a runtime service provider");
        }
        Set<String> undeclared = undeclaredInputs(definition);
        if (!undeclared.isEmpty()) {
            score += 2;
            factors.add("binds undeclared request input(s): " + String.join(", ", undeclared));
        }
        if (definition.response() != null && definition.response().file() != null) {
            score += 1;
            factors.add("generates a file download");
        }

        String mode = write && !authenticated ? "advanced" : usesService ? "extended" : "managed";
        String source = manifest.appHome().relativize(route.source()).toString().replace('\\', '/');
        return new Assessment(definition.id(), source, mode, score, List.copyOf(factors),
                sha256(route));
    }

    private static boolean isWrite(RouteFile route) {
        RouteDefinition definition = route.definition();
        if ("command-json".equals(definition.recipe())) {
            return true;
        }
        if (definition.sql() != null && "update".equals(definition.sql().effectiveMode())) {
            return true;
        }
        return WRITE_METHODS.contains(route.httpMethod().toUpperCase(Locale.ROOT));
    }

    private static boolean isAuthenticated(RouteDefinition definition) {
        return definition.security() != null
                && definition.security().auth() != null
                && !"public".equalsIgnoreCase(definition.security().auth());
    }

    private static boolean usesService(RouteDefinition definition) {
        if (definition.sql() != null && definition.sql().isService()) {
            return true;
        }
        return definition.queries().values().stream().anyMatch(SqlBinding::isService);
    }

    /** Request-sourced bind expressions whose input was never declared (no validation applies). */
    private static Set<String> undeclaredInputs(RouteDefinition definition) {
        Set<String> undeclared = new TreeSet<>();
        collectUndeclared(definition, definition.sql(), undeclared);
        definition.queries().values()
                .forEach(binding -> collectUndeclared(definition, binding, undeclared));
        return undeclared;
    }

    private static void collectUndeclared(RouteDefinition definition, SqlBinding binding,
            Set<String> into) {
        if (binding == null) {
            return;
        }
        for (String expr : binding.params().values()) {
            if (expr.startsWith("query.") || expr.startsWith("body.")
                    || expr.startsWith("params.")) {
                String name = expr.substring(expr.indexOf('.') + 1);
                int dot = name.indexOf('.');
                String first = dot < 0 ? name : name.substring(0, dot);
                if (!definition.input().containsKey(first)) {
                    into.add(first);
                }
            }
        }
    }

    private static String sha256(RouteFile route) {
        try {
            return Hashing.sha256(Files.readAllBytes(route.source()));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
