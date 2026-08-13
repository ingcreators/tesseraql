package io.tesseraql.yaml.lint;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.model.RouteDefinition;
import java.util.List;

/**
 * HTTP acquisition sources and the egress allow-list they are checked
 * against, shared by routes, enrichments and batch steps.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class HttpSourceRules {

    private HttpSourceRules() {
    }

    /** The sources whose arm is an outbound call, in authored order. */
    static java.util.Map<String, io.tesseraql.yaml.model.HttpSourceSpec> httpArms(
            RouteDefinition definition) {
        var calls = new java.util.LinkedHashMap<String, io.tesseraql.yaml.model.HttpSourceSpec>();
        definition.sources().forEach((name, binding) -> {
            if (binding != null && binding.isHttp()) {
                calls.put(name, binding.http());
            }
        });
        return calls;
    }

    /**
     * Egress lints for the {@code http} arm of a source (docs/connectors.md, "HTTP sources"):
     * an outbound acquisition belongs to a read recipe or a transactional one — never, say, a
     * file-import (TQL-YAML-1022) — and each clears the same egress checks a job's outbound
     * step does (TQL-SEC-4070/4071/4072 via {@link #lintHttpCall}).
     *
     * <p>The shadowing check the http map needed is gone with the map: a source cannot collide
     * with another source when they share one namespace, which is the point of sharing it.
     */
    static void lintHttpSources(AppConfig config, RouteDefinition definition, String source,
            List<LintFinding> findings) {
        var calls = httpArms(definition);
        if (calls.isEmpty()) {
            return;
        }
        String recipe = definition.recipe();
        boolean read = "query-json".equals(recipe) || "query-html".equals(recipe)
                || "page".equals(recipe);
        // A command's sources run before its transaction, so the write never waits on a
        // partner (docs/lookups.md, decision 19). Every other recipe still has no place for one.
        boolean write = DocumentRules.TRANSACTIONAL_DATASOURCE_RECIPES.contains(recipe);
        if (!read && !write) {
            findings.add(new LintFinding("TQL-YAML-1022", "error", source,
                    "an http: source is supported on query recipes (query-json,"
                            + " query-html, page) and on transactional ones ("
                            + String.join(", ",
                                    new java.util.TreeSet<>(
                                            DocumentRules.TRANSACTIONAL_DATASOURCE_RECIPES))
                            + "), not '"
                            + recipe + "'"));
        }
        calls.forEach((name, spec) -> {
            // The call happens before the transaction and a rollback cannot un-make it, so on a
            // write the author states that it is a reference. The framework can guarantee the
            // declaration exists, never that it is true.
            if (write && !spec.isReadOnly()) {
                findings.add(new LintFinding("TQL-YAML-1050", "error", source,
                        "http: source '" + name + "' on a '" + recipe + "' route needs"
                                + " readOnly: true — the call is made before the transaction and"
                                + " a rollback cannot un-make it, so a call with a side effect"
                                + " belongs after the commit, on the outbox"));
            }
            lintHttpCall(config, name, spec.call(), source, findings);
        });
    }

    /**
     * Statically checks an {@code httpCall} step's egress (roadmap Phase 26): the target host
     * must resolve to an allow-listed host ({@code TQL-SEC-4070}, deny by default), the url must be
     * an absolute http/https URL ({@code TQL-SEC-4071}), and a referenced credential should be
     * configured ({@code TQL-SEC-4072}, a warning since another environment may declare it). A url
     * carrying an unresolved {@code ${...}} secret in its host cannot be checked statically and is
     * left to the runtime's identical deny-by-default guard.
     */
    static void lintHttpCall(AppConfig config, String id,
            io.tesseraql.yaml.model.HttpCallSpec spec, String source, List<LintFinding> findings) {
        // A body on a method that carries none used to be documented as "ignored"; the client
        // in fact sends it, and either way the author asked for something that does not
        // happen. Refuse it at build time (docs/lookups.md, decision 16).
        if (spec.body() != null && !spec.body().isBlank() && !spec.carriesBody()) {
            findings.add(new LintFinding("TQL-YAML-1049", "error", source,
                    "'" + id + "': body: is declared with method " + spec.effectiveMethod()
                            + ", which carries no request body — declare the method that does"
                            + " (POST, PUT, PATCH), or drop the body"));
        }
        String resolved = null;
        if (spec.url() != null && !spec.url().isBlank()) {
            try {
                resolved = config.resolve(spec.url());
            } catch (RuntimeException ex) {
                resolved = spec.url();
            }
        }
        String host = null;
        String scheme = null;
        if (resolved != null) {
            try {
                java.net.URI uri = java.net.URI.create(resolved);
                host = uri.getHost();
                scheme = uri.getScheme();
            } catch (RuntimeException ex) {
                host = null;
            }
        }
        boolean absoluteHttp = host != null && scheme != null
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        if (!absoluteHttp) {
            // Flag a genuinely missing or relative url, but not one we merely cannot resolve yet
            // (an unresolved ${...} secret in the host is checked by the runtime instead).
            if (resolved == null || !resolved.contains("${")) {
                findings.add(new LintFinding("TQL-SEC-4071", "error", source,
                        "http: '" + id + "' needs an absolute http or https url:"));
            }
            lintHttpCredential(config, id, spec, source, findings);
            return;
        }
        List<String> allowedHosts = new java.util.ArrayList<>();
        if (config.navigate("tesseraql.http.outbound.allowedHosts") instanceof List<?> declared) {
            declared.forEach(value -> allowedHosts.add(String.valueOf(value)));
        }
        if (!io.tesseraql.yaml.http.HttpOutbound.hostAllowed(allowedHosts, host)) {
            findings.add(new LintFinding("TQL-SEC-4070", "error", source, "http: '" + id
                    + "' targets host '" + host + "' which is not in"
                    + " tesseraql.http.outbound.allowedHosts (deny by default)"));
        }
        lintHttpCredential(config, id, spec, source, findings);
    }

    static void lintHttpCredential(AppConfig config, String id,
            io.tesseraql.yaml.model.HttpCallSpec spec, String source, List<LintFinding> findings) {
        String credential = spec.credential();
        if (credential == null || credential.isBlank()) {
            return;
        }
        if (config.navigate("tesseraql.http.outbound.credentials." + credential) == null) {
            findings.add(new LintFinding("TQL-SEC-4072", "warning", source, "http: '" + id
                    + "' references undeclared credential '" + credential + "'"));
        }
    }
}
