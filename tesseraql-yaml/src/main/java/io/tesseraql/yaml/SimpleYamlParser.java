package io.tesseraql.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    /** Parses a route YAML file. */
    public RouteDefinition parseRoute(Path file) {
        try {
            String content = Files.readString(file);
            return validate(mapper.readValue(content, RouteDefinition.class), file.toString());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (TqlException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw TqlException.builder(SCHEMA_ERROR)
                    .message("Failed to parse route YAML: " + ex.getMessage())
                    .source(file.toString())
                    .cause(ex)
                    .build();
        }
    }

    /** Parses a route YAML string (mainly for tests). */
    public RouteDefinition parseRoute(String yaml, String source) {
        try {
            return validate(mapper.readValue(yaml, RouteDefinition.class), source);
        } catch (IOException ex) {
            throw TqlException.builder(SCHEMA_ERROR)
                    .message("Failed to parse route YAML: " + ex.getMessage())
                    .source(source)
                    .cause(ex)
                    .build();
        }
    }

    /** Parses a job YAML file. */
    public JobDefinition parseJob(Path file) {
        try {
            JobDefinition job = mapper.readValue(Files.readString(file), JobDefinition.class);
            return validateJob(job, file.toString());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (TqlException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw TqlException.builder(SCHEMA_ERROR)
                    .message("Failed to parse job YAML: " + ex.getMessage())
                    .source(file.toString())
                    .cause(ex)
                    .build();
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
        try {
            io.tesseraql.yaml.model.ScopeDefinition scope = mapper.readValue(
                    Files.readString(file), io.tesseraql.yaml.model.ScopeDefinition.class);
            return validateScope(scope, file.toString());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (TqlException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw TqlException.builder(SCHEMA_ERROR)
                    .message("Failed to parse scope YAML: " + ex.getMessage())
                    .source(file.toString())
                    .cause(ex)
                    .build();
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

    /** Parses a workflow YAML file (roadmap Phase 28). */
    public io.tesseraql.yaml.model.WorkflowDefinition parseWorkflow(Path file) {
        try {
            io.tesseraql.yaml.model.WorkflowDefinition workflow = mapper.readValue(
                    Files.readString(file), io.tesseraql.yaml.model.WorkflowDefinition.class);
            return validateWorkflow(workflow, file.toString());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (TqlException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw TqlException.builder(SCHEMA_ERROR)
                    .message("Failed to parse workflow YAML: " + ex.getMessage())
                    .source(file.toString())
                    .cause(ex)
                    .build();
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

    /** Parses an arbitrary YAML document into a nested map (for config files). */
    public Map<String, Object> parseTree(Path file) {
        try {
            String content = Files.readString(file);
            if (content.isBlank()) {
                return Map.of();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> tree = mapper.readValue(content, Map.class);
            return tree == null ? Map.of() : tree;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
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
