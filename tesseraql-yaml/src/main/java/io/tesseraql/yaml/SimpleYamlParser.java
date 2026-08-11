package io.tesseraql.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.model.JobDefinition;
import io.tesseraql.yaml.model.RouteDefinition;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Parses and validates TesseraQL Simple YAML into the route model (design ch. 6).
 *
 * <p>Validation here is structural and intentionally light; the JSON Schema
 * ({@code schema/tesseraql-v1.schema.json}) documents the full contract, and the lint goal plus
 * the route compiler enforce the semantic rules. Failures raise {@code TQL-YAML-*} codes.
 */
public final class SimpleYamlParser {

    private static final TqlErrorCode SCHEMA_ERROR = new TqlErrorCode(TqlDomain.YAML, 1001);
    private static final String EXPECTED_VERSION = "tesseraql/v1";

    private final ObjectMapper mapper = YamlMappers.constrained();

    /**
     * Reads a file's text, mapping a genuine read failure (a missing or unreadable file — an
     * internal error, not malformed input) to {@link UncheckedIOException}. Parse failures are a
     * separate concern handled by each caller, and always surface as a coded {@code TQL-YAML-1001}.
     */
    private static String readFile(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * The one malformed-YAML rejection: whether the failure came from Jackson (an
     * {@link IOException}), SnakeYAML's resource limits (a {@code RuntimeException}), or a
     * structural check, every parse path lands here as {@code TQL-YAML-1001} — so a caller (a
     * boot loader or a runtime editor endpoint) never sees a raw library exception.
     */
    private static TqlException schemaError(String what, String source, Throwable ex) {
        return TqlException.builder(SCHEMA_ERROR)
                .message("Failed to parse " + what + " YAML: " + ex.getMessage())
                .source(source)
                .cause(ex)
                .build();
    }

    /** Parses a route YAML file. */
    public RouteDefinition parseRoute(Path file) {
        return parseRoute(readFile(file), file.toString());
    }

    /** Parses a route YAML string (mainly for tests). */
    public RouteDefinition parseRoute(String yaml, String source) {
        try {
            return validate(mapper.readValue(yaml, RouteDefinition.class), source);
        } catch (TqlException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw schemaError("route", source, ex);
        }
    }

    /** The keys a field domain may carry (docs/field-domains.md): the field itself, never the
     * operation's use of it. */
    private static final java.util.Set<String> DOMAIN_KEYS = java.util.Set.of("type", "min",
            "max", "minLength", "maxLength", "pattern", "format", "enum", "items",
            "classification", "mask", "widget");
    private static final java.util.Set<String> CONSTRAINT_KEYS = java.util.Set.of("field", "code",
            "message");

    private static final TqlErrorCode DOMAIN_OPERATIONAL_KEY = new TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.FIELD, 4602);
    private static final TqlErrorCode DOMAIN_MALFORMED = new TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.FIELD, 4603);

    /**
     * Parses one {@code domains/*.yml} document (docs/field-domains.md) into its declared
     * domains and constraint-catalog entries, rejecting operational keys inside a domain
     * ({@code TQL-FIELD-4602}) — a domain describes the field itself; {@code required},
     * {@code default}, and {@code writable} belong to each route's use of it.
     */
    public io.tesseraql.yaml.model.DomainsDocument parseDomains(Path file) {
        com.fasterxml.jackson.databind.JsonNode tree;
        try {
            tree = mapper.readTree(readFile(file));
        } catch (IOException | RuntimeException ex) {
            throw schemaError("domains", file.toString(), ex);
        }
        if (tree == null || !tree.isObject()) {
            throw new TqlException(DOMAIN_MALFORMED,
                    "Domains document " + file + " must be a map");
        }
        if (!EXPECTED_VERSION.equals(tree.path("version").asText(null))) {
            throw new TqlException(DOMAIN_MALFORMED, "Domains document " + file
                    + " must declare version: " + EXPECTED_VERSION);
        }
        java.util.Map<String, io.tesseraql.yaml.model.InputField> domains = new java.util.LinkedHashMap<>();
        for (var entry : tree.path("domains").properties()) {
            for (String key : (Iterable<String>) () -> entry.getValue().fieldNames()) {
                if (!DOMAIN_KEYS.contains(key)) {
                    throw new TqlException(DOMAIN_OPERATIONAL_KEY, "Domain '" + entry.getKey()
                            + "' (" + file + ") declares '" + key + "' — a domain describes the"
                            + " field itself; required/default/writable belong to each route's"
                            + " use of it");
                }
            }
            domains.put(entry.getKey(), mapper.convertValue(entry.getValue(),
                    io.tesseraql.yaml.model.InputField.class));
        }
        java.util.Map<String, io.tesseraql.yaml.model.ErrorsSpec.ConstraintMapping> constraints = new java.util.LinkedHashMap<>();
        for (var entry : tree.path("constraints").properties()) {
            for (String key : (Iterable<String>) () -> entry.getValue().fieldNames()) {
                if (!CONSTRAINT_KEYS.contains(key)) {
                    // Mirror the strict domains: check — ConstraintMapping is ignoreUnknown, so a
                    // `feild:` typo would otherwise drop the field mapping and surface the DB
                    // violation as an opaque error instead of a field error.
                    throw new TqlException(DOMAIN_OPERATIONAL_KEY, "Constraint '" + entry.getKey()
                            + "' (" + file + ") declares '" + key + "' — a constraint mapping"
                            + " accepts only field, code, message");
                }
            }
            constraints.put(entry.getKey(), mapper.convertValue(entry.getValue(),
                    io.tesseraql.yaml.model.ErrorsSpec.ConstraintMapping.class));
        }
        return new io.tesseraql.yaml.model.DomainsDocument(domains, constraints);
    }

    /** TQL-FIELD-4616: a catalogs document is malformed or declares an unknown key. */
    private static final TqlErrorCode CATALOG_MALFORMED = new TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.FIELD, 4616);

    /** The keys a catalog may carry (docs/lookups.md): where the codes are, and which columns. */
    private static final java.util.Set<String> CATALOG_KEYS = java.util.Set.of("table", "where",
            "key", "label", "order", "active", "datasource", "cache");

    /** Parses a {@code catalogs/*.yml} document (docs/lookups.md, decision 9). */
    public java.util.Map<String, io.tesseraql.yaml.model.CatalogSpec> parseCatalogs(Path file) {
        com.fasterxml.jackson.databind.JsonNode tree;
        try {
            tree = mapper.readTree(readFile(file));
        } catch (IOException | RuntimeException ex) {
            throw schemaError("catalogs", file.toString(), ex);
        }
        if (tree == null || !tree.isObject()) {
            throw new TqlException(CATALOG_MALFORMED,
                    "Catalogs document " + file + " must be a map");
        }
        if (!EXPECTED_VERSION.equals(tree.path("version").asText(null))) {
            throw new TqlException(CATALOG_MALFORMED, "Catalogs document " + file
                    + " must declare version: " + EXPECTED_VERSION);
        }
        java.util.Map<String, io.tesseraql.yaml.model.CatalogSpec> catalogs = new java.util.LinkedHashMap<>();
        for (var entry : tree.path("catalogs").properties()) {
            for (String key : (Iterable<String>) () -> entry.getValue().fieldNames()) {
                if (!CATALOG_KEYS.contains(key)) {
                    // Strict like domains: a `lable:` typo would otherwise leave every code
                    // rendering as its raw value with nothing to explain why.
                    throw new TqlException(CATALOG_MALFORMED, "Catalog '" + entry.getKey()
                            + "' (" + file + ") declares '" + key + "' — a catalog accepts only "
                            + String.join(", ", new java.util.TreeSet<>(CATALOG_KEYS)));
                }
            }
            catalogs.put(entry.getKey(), mapper.convertValue(entry.getValue(),
                    io.tesseraql.yaml.model.CatalogSpec.class));
        }
        return catalogs;
    }

    private static final TqlErrorCode RULESET_MALFORMED = new TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.FIELD, 4604);

    /**
     * Parses one {@code rules/*.yml} document (docs/validation-rule-sets.md), rejecting an
     * entry that declares both or neither of {@code rule:}/{@code file:} — a shared rule is
     * exactly one of an expression or a SQL file.
     */
    public io.tesseraql.yaml.model.RuleSetsDocument parseRuleSets(Path file) {
        io.tesseraql.yaml.model.RuleSetsDocument document;
        try {
            document = mapper.readValue(readFile(file),
                    io.tesseraql.yaml.model.RuleSetsDocument.class);
        } catch (IOException | RuntimeException ex) {
            throw schemaError("rules", file.toString(), ex);
        }
        if (document == null) {
            throw new TqlException(RULESET_MALFORMED, "Empty rules document: " + file);
        }
        if (!EXPECTED_VERSION.equals(document.version())) {
            throw new TqlException(RULESET_MALFORMED, "Rules document " + file
                    + " must declare version: " + EXPECTED_VERSION);
        }
        document.rules().forEach((name, rule) -> {
            boolean expression = rule.rule() != null && !rule.rule().isBlank();
            boolean sql = rule.file() != null && !rule.file().isBlank();
            if (expression == sql) {
                throw new TqlException(RULESET_MALFORMED, "Rule '" + name + "' (" + file
                        + ") must declare exactly one of rule: or file:");
            }
        });
        return document;
    }

    private static final TqlErrorCode DECISIONS_MALFORMED = new TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.DECISION, 4700);

    /**
     * Parses one {@code decisions/*.yml} document (docs/decision-tables.md). Validation here is
     * structural — a map with the version, decisions deserialized into the model; the contract
     * and every row compile in {@link io.tesseraql.yaml.decision.DecisionSets} so a bad match
     * kind, cell literal, or overlapping unique rows carry their own codes.
     */
    public io.tesseraql.yaml.model.DecisionsDocument parseDecisions(Path file) {
        io.tesseraql.yaml.model.DecisionsDocument document;
        try {
            document = mapper.readValue(readFile(file),
                    io.tesseraql.yaml.model.DecisionsDocument.class);
        } catch (IOException | RuntimeException ex) {
            throw schemaError("decisions", file.toString(), ex);
        }
        if (document == null) {
            throw new TqlException(DECISIONS_MALFORMED, "Empty decisions document: " + file);
        }
        if (!EXPECTED_VERSION.equals(document.version())) {
            throw new TqlException(DECISIONS_MALFORMED, "Decisions document " + file
                    + " must declare version: " + EXPECTED_VERSION);
        }
        return document;
    }

    private static final TqlErrorCode CALENDARS_MALFORMED = new TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.BATCH, 4205);

    /**
     * Parses one {@code calendars/*.yml} document (docs/batch-platform.md track B). Validation
     * here is structural — day names, date literals, and source columns are checked in
     * {@link io.tesseraql.yaml.calendar.Calendars} where the app-wide namespace is built.
     */
    public io.tesseraql.yaml.model.CalendarsDocument parseCalendars(Path file) {
        io.tesseraql.yaml.model.CalendarsDocument document;
        try {
            document = mapper.readValue(readFile(file),
                    io.tesseraql.yaml.model.CalendarsDocument.class);
        } catch (IOException | RuntimeException ex) {
            throw schemaError("calendars", file.toString(), ex);
        }
        if (document == null) {
            throw new TqlException(CALENDARS_MALFORMED, "Empty calendars document: " + file);
        }
        if (!EXPECTED_VERSION.equals(document.version())) {
            throw new TqlException(CALENDARS_MALFORMED, "Calendars document " + file
                    + " must declare version: " + EXPECTED_VERSION);
        }
        return document;
    }

    /** Parses a job YAML file. */
    public JobDefinition parseJob(Path file) {
        String content = readFile(file);
        try {
            return validateJob(mapper.readValue(content, JobDefinition.class), file.toString());
        } catch (TqlException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw schemaError("job", file.toString(), ex);
        }
    }

    private JobDefinition validateJob(JobDefinition job, String source) {
        if (job == null) {
            throw error("Empty job document", source);
        }
        requireField(job.version(), "version", source);
        if (!EXPECTED_VERSION.equals(job.version())) {
            throw error("Unsupported version '" + job.version() + "', expected " + EXPECTED_VERSION,
                    source);
        }
        requireField(job.id(), "id", source);
        requireField(job.kind(), "kind", source);
        requireField(job.recipe(), "recipe", source);
        return job;
    }

    /** Parses a scope YAML file (roadmap Phase 29). */
    public io.tesseraql.yaml.model.ScopeDefinition parseScope(Path file) {
        String content = readFile(file);
        try {
            return validateScope(mapper.readValue(
                    content, io.tesseraql.yaml.model.ScopeDefinition.class), file.toString());
        } catch (TqlException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw schemaError("scope", file.toString(), ex);
        }
    }

    private io.tesseraql.yaml.model.ScopeDefinition validateScope(
            io.tesseraql.yaml.model.ScopeDefinition scope, String source) {
        if (scope == null) {
            throw error("Empty scope document", source);
        }
        requireField(scope.version(), "version", source);
        if (!EXPECTED_VERSION.equals(scope.version())) {
            throw error("Unsupported version '" + scope.version() + "', expected "
                    + EXPECTED_VERSION, source);
        }
        requireField(scope.id(), "id", source);
        requireField(scope.kind(), "kind", source);
        return scope;
    }

    /** Parses an attachment YAML file (roadmap Phase 30). */
    public io.tesseraql.yaml.model.AttachmentDefinition parseAttachment(Path file) {
        String content = readFile(file);
        try {
            return validateAttachment(mapper.readValue(
                    content, io.tesseraql.yaml.model.AttachmentDefinition.class), file.toString());
        } catch (TqlException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw schemaError("attachment", file.toString(), ex);
        }
    }

    private io.tesseraql.yaml.model.AttachmentDefinition validateAttachment(
            io.tesseraql.yaml.model.AttachmentDefinition attachment, String source) {
        if (attachment == null) {
            throw error("Empty attachment document", source);
        }
        requireField(attachment.version(), "version", source);
        if (!EXPECTED_VERSION.equals(attachment.version())) {
            throw error("Unsupported version '" + attachment.version() + "', expected "
                    + EXPECTED_VERSION, source);
        }
        requireField(attachment.id(), "id", source);
        requireField(attachment.kind(), "kind", source);
        return attachment;
    }

    /** Parses a workflow YAML file (roadmap Phase 28). */
    public io.tesseraql.yaml.model.WorkflowDefinition parseWorkflow(Path file) {
        String content = readFile(file);
        try {
            return validateWorkflow(mapper.readValue(
                    content, io.tesseraql.yaml.model.WorkflowDefinition.class), file.toString());
        } catch (TqlException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw schemaError("workflow", file.toString(), ex);
        }
    }

    private io.tesseraql.yaml.model.WorkflowDefinition validateWorkflow(
            io.tesseraql.yaml.model.WorkflowDefinition workflow, String source) {
        if (workflow == null) {
            throw error("Empty workflow document", source);
        }
        requireField(workflow.version(), "version", source);
        if (!EXPECTED_VERSION.equals(workflow.version())) {
            throw error("Unsupported version '" + workflow.version() + "', expected "
                    + EXPECTED_VERSION, source);
        }
        requireField(workflow.id(), "id", source);
        requireField(workflow.kind(), "kind", source);
        requireField(workflow.initial(), "initial", source);
        return workflow;
    }

    /**
     * Serializes a tree (nested maps/lists/scalars) back to a YAML document — the inverse of
     * {@link #parseTree(Path)}, for a Studio-managed config file such as {@code config/overlay.yml}.
     * Comments and original formatting are not preserved (a fresh document is emitted).
     */
    public String write(Object tree) {
        try {
            return mapper.writeValueAsString(tree);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new TqlException(SCHEMA_ERROR, "Failed to serialize YAML: " + ex.getMessage());
        }
    }

    /** Parses an arbitrary YAML document into a nested map (for config files). */
    public Map<String, Object> parseTree(Path file) {
        String content = readFile(file);
        if (content.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> tree = mapper.readValue(content, Map.class);
            return tree == null ? Map.of() : tree;
        } catch (TqlException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw schemaError("config", file.toString(), ex);
        }
    }

    /**
     * Parses an arbitrary YAML (or JSON, a YAML subset) document string into a tree — e.g. a Studio
     * sample-data model entered in the editor. A blank document yields an empty map; a malformed one
     * raises {@code TQL-YAML-1001} carrying the parser message, rather than an {@link IOException},
     * so callers can surface it to the user.
     */
    public Map<String, Object> parseTree(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> tree = mapper.readValue(yaml, Map.class);
            return tree == null ? Map.of() : tree;
        } catch (TqlException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw schemaError("config", "<string>", ex);
        }
    }

    private RouteDefinition validate(RouteDefinition route, String source) {
        if (route == null) {
            throw error("Empty route document", source);
        }
        requireField(route.version(), "version", source);
        if (!EXPECTED_VERSION.equals(route.version())) {
            throw error(
                    "Unsupported version '" + route.version() + "', expected " + EXPECTED_VERSION,
                    source);
        }
        requireField(route.id(), "id", source);
        requireField(route.kind(), "kind", source);
        requireField(route.recipe(), "recipe", source);
        return route;
    }

    private void requireField(String value, String field, String source) {
        if (value == null || value.isBlank()) {
            throw error("Missing required field '" + field + "'", source);
        }
    }

    private TqlException error(String message, String source) {
        return TqlException.builder(SCHEMA_ERROR).message(message).source(source).build();
    }
}
