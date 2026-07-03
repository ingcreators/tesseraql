package io.tesseraql.yaml.manifest;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.SimpleYamlParser;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.model.RouteDefinition;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Loads an external app home into an {@link AppManifest} (design ch. 3, 4, 20).
 *
 * <p>Enforces the path-confinement guardrail: every file read must resolve inside the app home,
 * so {@code ../..} traversal is rejected (design ch. 20.1, 20.2).
 */
public final class ManifestLoader {

    private static final TqlErrorCode TRAVERSAL = new TqlErrorCode(TqlDomain.YAML, 1201);
    private static final TqlErrorCode LOAD_ERROR = new TqlErrorCode(TqlDomain.YAML, 1202);
    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete",
            "head", "options");

    private final SimpleYamlParser parser = new SimpleYamlParser();

    /** Loads the manifest rooted at {@code appHome}. */
    public AppManifest load(Path appHome) {
        return load(appHome, null);
    }

    /**
     * The active environment profile (roadmap Phase 46): the {@code tesseraql.env} system
     * property (the serve command's {@code --env} sets it) wins over the {@code TESSERAQL_ENV}
     * environment variable; blank means no profile layer. The name is constrained to a simple
     * token so the profile can never escape {@code config/env/}.
     */
    public static String activeProfile() {
        String profile = System.getProperty("tesseraql.env", System.getenv("TESSERAQL_ENV"));
        if (profile == null || profile.isBlank()) {
            return null;
        }
        String clean = profile.trim();
        if (!clean.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new TqlException(LOAD_ERROR,
                    "Invalid environment profile name: " + profile);
        }
        return clean;
    }

    /** One route document that failed to parse during a tolerant load (the hot reloader). */
    public record BrokenRoute(Path source, String error) {
    }

    /**
     * Loads the app, tolerating unparseable route documents when {@code brokenSink} is non-null:
     * each one is reported to the sink (source file and root message) and left out of the manifest
     * instead of aborting the load. The hot reloader (roadmap Phase 42) uses this so one broken
     * document on disk cannot take the whole apply-and-reload loop down; startup stays strict.
     */
    public AppManifest load(Path appHome, List<BrokenRoute> brokenSink) {
        Path home = appHome.toAbsolutePath().normalize();
        if (!Files.isDirectory(home)) {
            throw new TqlException(LOAD_ERROR, "App home is not a directory: " + home);
        }
        AppConfig config = loadConfig(home);
        List<RouteFile> routes = loadRoutes(home, brokenSink);
        List<JobFile> jobs = loadJobs(home);
        List<ToolFile> tools = new ArrayList<>();
        List<ResourceFile> resources = new ArrayList<>();
        List<UiResourceFile> uiResources = new ArrayList<>();
        List<PromptFile> prompts = new ArrayList<>();
        loadMcp(home, tools, resources, uiResources, prompts);
        List<RouteFile> consumers = loadConsumers(home);
        List<ScopeFile> scopes = loadScopes(home);
        List<WorkflowFile> workflows = loadWorkflows(home);
        List<AttachmentFile> attachments = loadAttachments(home);
        List<MigrationFile> migrations = loadMigrations(home);
        ManifestIndex index = buildIndex(home);
        return new AppManifest(home, config, routes, jobs, tools, resources, uiResources, consumers,
                scopes, workflows, attachments, migrations, prompts, index);
    }

    /**
     * Lists the app's Flyway migrations (spec layer; no DDL parsing), mirroring how
     * {@code AppMigrations} resolves them: the {@code main} set under {@code db/migration} and its
     * {@code db/migration-<vendor>} overlays, plus one set per named datasource under
     * {@code db/<datasource>/migration} (with {@code migration-<vendor>} overlays). The listing is
     * sorted into a deterministic order so the derived spec artifact stays byte-stable.
     */
    private List<MigrationFile> loadMigrations(Path home) {
        Path db = home.resolve("db");
        if (!Files.isDirectory(db)) {
            return List.of();
        }
        List<MigrationFile> migrations = new ArrayList<>();
        // The main datasource: db/migration and its db/migration-<vendor> overlay siblings.
        collectMigrationFamily(home, db, "main", migrations);
        // Each named datasource: db/<datasource>/migration and its overlays (the migration* dirs
        // under db/ are the main set, not datasources, so they are excluded here).
        try (Stream<Path> entries = Files.list(db)) {
            entries.filter(Files::isDirectory)
                    .filter(dir -> !dir.getFileName().toString().startsWith("migration"))
                    .forEach(dir -> collectMigrationFamily(home, dir,
                            dir.getFileName().toString(), migrations));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        Collections.sort(migrations);
        return migrations;
    }

    /**
     * Collects the migration family rooted at {@code parent}: the common {@code migration} directory
     * (vendor {@code null}) and each {@code migration-<vendor>} overlay sibling, all bound to
     * {@code datasource}.
     */
    private void collectMigrationFamily(Path home, Path parent, String datasource,
            List<MigrationFile> out) {
        collectMigrationDir(home, parent.resolve("migration"), datasource, null, out);
        try (Stream<Path> siblings = Files.list(parent)) {
            siblings.filter(Files::isDirectory)
                    .filter(dir -> dir.getFileName().toString().startsWith("migration-"))
                    .forEach(dir -> collectMigrationDir(home, dir, datasource,
                            dir.getFileName().toString().substring("migration-".length()), out));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Adds every Flyway-named SQL file found recursively under {@code dir} (as Flyway scans it). */
    private void collectMigrationDir(Path home, Path dir, String datasource, String vendor,
            List<MigrationFile> out) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                requireInside(home, file);
                MigrationFile migration = MigrationFile.parse(datasource, vendor, file);
                if (migration != null) {
                    out.add(migration);
                }
            });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Loads the {@code attachments/} tree (roadmap Phase 30): each {@code kind: attachment} document
     * binds uploaded files to an owning business record. An attachment document is not itself a
     * route — the compiler synthesizes the upload, list, and download routes — so it lives in its own
     * tree.
     */
    private List<AttachmentFile> loadAttachments(Path home) {
        Path attachmentRoot = home.resolve("attachments");
        if (!Files.isDirectory(attachmentRoot)) {
            return List.of();
        }
        List<AttachmentFile> attachments = new ArrayList<>();
        try (Stream<Path> files = Files.walk(attachmentRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .forEach(file -> {
                        requireInside(home, file);
                        attachments.add(new AttachmentFile(file, parser.parseAttachment(file)));
                    });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return attachments;
    }

    /**
     * Loads the {@code workflow/} tree (roadmap Phase 28): each {@code kind: workflow} document is a
     * SQL-contract state machine. A workflow is not itself a route — the compiler synthesizes one
     * transactional-command route per transition — so it lives in its own tree, alongside the 2-way
     * SQL command, assignee, and history files its transitions reference.
     */
    private List<WorkflowFile> loadWorkflows(Path home) {
        Path workflowRoot = home.resolve("workflow");
        if (!Files.isDirectory(workflowRoot)) {
            return List.of();
        }
        List<WorkflowFile> workflows = new ArrayList<>();
        try (Stream<Path> files = Files.walk(workflowRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .forEach(file -> {
                        requireInside(home, file);
                        workflows.add(new WorkflowFile(file, parser.parseWorkflow(file)));
                    });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return workflows;
    }

    /**
     * Loads the {@code scope/} tree (roadmap Phase 29): each {@code kind: scope} document is a named
     * row-level predicate derived from the principal, applied to a query through a
     * {@code /*%scope%/} directive. Scope documents are not routes — they carry no HTTP binding — so
     * they live in their own tree, alongside the 2-way SQL fragment files their match arms reference.
     */
    private List<ScopeFile> loadScopes(Path home) {
        Path scopeRoot = home.resolve("scope");
        if (!Files.isDirectory(scopeRoot)) {
            return List.of();
        }
        List<ScopeFile> scopes = new ArrayList<>();
        try (Stream<Path> files = Files.walk(scopeRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .forEach(file -> {
                        requireInside(home, file);
                        scopes.add(new ScopeFile(file, parser.parseScope(file)));
                    });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return scopes;
    }

    /**
     * Loads the {@code consume/} tree (roadmap Phase 27): each {@code queue-consume} route subscribes
     * to a messaging channel and runs its SQL pipeline per message. A consumer is not mounted on
     * HTTP — the runtime's messaging consumer drives it — so it lives outside {@code web/} and the
     * derived "method" is the synthetic {@code QUEUE} marker, the directory tree its logical path.
     */
    private List<RouteFile> loadConsumers(Path home) {
        Path consumeRoot = home.resolve("consume");
        if (!Files.isDirectory(consumeRoot)) {
            return List.of();
        }
        List<RouteFile> consumers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(consumeRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .forEach(file -> {
                        requireInside(home, file);
                        RouteDefinition definition = parser.parseRoute(file);
                        Path relative = consumeRoot.relativize(file);
                        consumers.add(new RouteFile("QUEUE", "/" + relative.toString()
                                .replace(java.io.File.separatorChar, '/'), file, definition));
                    });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return consumers;
    }

    private AppConfig loadConfig(Path home) {
        // Sources are deep-merged in precedence order so an install overlay can override nested
        // keys (for example a single datasource) without replacing whole config sub-trees (ch. 32.6).
        // The environment profile layer (roadmap Phase 46) sits between the app's base config and
        // Studio's overlay: the profile is the environment's tuning, and dev-time Studio edits
        // still win on top of it.
        Map<String, Object> merged = new HashMap<>();
        deepMerge(merged, parseTreeIfPresent(home.resolve("config/application.yml")));
        deepMerge(merged, parseTreeIfPresent(home.resolve("config/tesseraql.yml")));
        String profile = activeProfile();
        if (profile != null) {
            Path profileFile = home.resolve("config/env/" + profile + ".yml");
            if (!Files.isRegularFile(profileFile)) {
                // Fail fast: a typo'd environment must never silently run another env's config.
                throw new TqlException(LOAD_ERROR, "Environment profile '" + profile
                        + "' is active but config/env/" + profile + ".yml does not exist");
            }
            deepMerge(merged, parseTreeIfPresent(profileFile));
        }
        deepMerge(merged, parseTreeIfPresent(home.resolve("config/overlay.yml")));

        // Provide the app home for ${TESSERAQL_APP_HOME} placeholders, deferring to real env vars.
        String homePath = home.toString();
        AppConfig.EnvironmentSource environment = name -> {
            String value = System.getenv(name);
            if (value != null) {
                return value;
            }
            return "TESSERAQL_APP_HOME".equals(name) ? homePath : null;
        };
        return new AppConfig(merged, environment);
    }

    private Map<String, Object> parseTreeIfPresent(Path file) {
        return Files.isRegularFile(file) ? parser.parseTree(file) : Map.of();
    }

    /** Recursively merges {@code incoming} into {@code base}; nested maps merge, leaves override. */
    @SuppressWarnings("unchecked")
    private static void deepMerge(Map<String, Object> base, Map<String, Object> incoming) {
        for (Map.Entry<String, Object> entry : incoming.entrySet()) {
            Object newValue = entry.getValue();
            Object oldValue = base.get(entry.getKey());
            if (oldValue instanceof Map && newValue instanceof Map) {
                Map<String, Object> child = new HashMap<>((Map<String, Object>) oldValue);
                deepMerge(child, (Map<String, Object>) newValue);
                base.put(entry.getKey(), child);
            } else {
                base.put(entry.getKey(), newValue);
            }
        }
    }

    private List<RouteFile> loadRoutes(Path home, List<BrokenRoute> brokenSink) {
        Path webRoot = home.resolve("web");
        if (!Files.isDirectory(webRoot)) {
            return List.of();
        }
        List<RouteFile> routes = new ArrayList<>();
        try (Stream<Path> files = Files.walk(webRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yml"))
                    .filter(p -> isMethodFile(p.getFileName().toString()))
                    .sorted()
                    .forEach(file -> {
                        if (brokenSink == null) {
                            routes.add(toRouteFile(home, webRoot, file));
                            return;
                        }
                        try {
                            routes.add(toRouteFile(home, webRoot, file));
                        } catch (RuntimeException ex) {
                            brokenSink.add(new BrokenRoute(file, rootMessage(ex)));
                        }
                    });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return routes;
    }

    private static String rootMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.toString() : root.getMessage();
    }

    private List<JobFile> loadJobs(Path home) {
        Path batchRoot = home.resolve("batch");
        if (!Files.isDirectory(batchRoot)) {
            return List.of();
        }
        List<JobFile> jobs = new ArrayList<>();
        try (Stream<Path> files = Files.walk(batchRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .forEach(file -> {
                        requireInside(home, file);
                        jobs.add(new JobFile(file, parser.parseJob(file)));
                    });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return jobs;
    }

    /**
     * Loads the {@code mcp/} tree, splitting each document by {@code kind}: a {@code resource}
     * document becomes a {@link ResourceFile} (read-only JSON context addressed by its {@code uri}),
     * a {@code ui} document a {@link UiResourceFile} (an MCP Apps UI resource that renders an
     * {@code hc-*} fragment, addressed by its {@code ui://} uri), and everything else a
     * {@link ToolFile} (whose {@code ui:} field, when present, links it to a UI resource). All reuse
     * the route model (recipe, input, sql, security); the {@code description} is the model-facing
     * hint read from the same document.
     */
    private void loadMcp(Path home, List<ToolFile> tools, List<ResourceFile> resources,
            List<UiResourceFile> uiResources, List<PromptFile> prompts) {
        Path mcpRoot = home.resolve("mcp");
        if (!Files.isDirectory(mcpRoot)) {
            return;
        }
        try (Stream<Path> files = Files.walk(mcpRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .forEach(file -> {
                        requireInside(home, file);
                        Map<String, Object> tree = parser.parseTree(file);
                        String description = string(tree.get("description"));
                        Object kind = tree.get("kind");
                        // A prompt is pure text (no recipe/SQL), so it is parsed from the tree
                        // directly rather than through the route parser (which requires a recipe).
                        if ("prompt".equals(kind)) {
                            prompts.add(promptFile(file, tree, description));
                            return;
                        }
                        RouteDefinition definition = parser.parseRoute(file);
                        if ("resource".equals(kind)) {
                            resources.add(new ResourceFile(file, definition, description,
                                    string(tree.get("uri")), string(tree.get("mimeType"))));
                        } else if ("ui".equals(kind)) {
                            uiResources.add(new UiResourceFile(file, definition, description,
                                    string(tree.get("uri")),
                                    io.tesseraql.yaml.model.UiSpec.from(tree.get("ui"))));
                        } else {
                            tools.add(new ToolFile(file, definition, description,
                                    string(tree.get("ui"))));
                        }
                    });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Parses a {@code kind: prompt} document into a {@link PromptFile} (id, arguments, template). */
    @SuppressWarnings("unchecked")
    private static PromptFile promptFile(Path file, Map<String, Object> tree, String description) {
        List<PromptFile.Argument> arguments = new ArrayList<>();
        if (tree.get("input") instanceof Map<?, ?> input) {
            for (Map.Entry<?, ?> entry : input.entrySet()) {
                String name = String.valueOf(entry.getKey());
                String argDescription = null;
                boolean required = false;
                if (entry.getValue() instanceof Map<?, ?> spec) {
                    argDescription = string(((Map<String, Object>) spec).get("description"));
                    required = Boolean.TRUE.equals(((Map<String, Object>) spec).get("required"));
                }
                arguments.add(new PromptFile.Argument(name, argDescription, required));
            }
        }
        return new PromptFile(file, string(tree.get("id")), description, arguments,
                string(tree.get("template")));
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private RouteFile toRouteFile(Path home, Path webRoot, Path file) {
        requireInside(home, file);
        RouteDefinition definition = parser.parseRoute(file);
        String fileName = file.getFileName().toString();
        String method = fileName.substring(0, fileName.length() - ".yml".length()).toUpperCase();

        Path relative = webRoot.relativize(file).getParent();
        List<String> segments = new ArrayList<>();
        if (relative != null) {
            for (Path segment : relative) {
                segments.add(segment.toString());
            }
        }
        // web/ mirrors the URL space one-to-one (design ch. 4.2): no directory is special-cased,
        // so the URL is always predictable from the file path. API vs page vs fragment is a URL
        // convention (/api/..., /users/..., .../fragments/<name>), not a folder rule.
        String urlPath = "/" + String.join("/", segments);
        return new RouteFile(method, urlPath, file, definition);
    }

    private static boolean isMethodFile(String fileName) {
        String stem = fileName.substring(0, fileName.length() - ".yml".length());
        return HTTP_METHODS.contains(stem);
    }

    /** Marker file PostgreSQL writes at the root of every initialized data directory. */
    private static final String PG_DATA_MARKER = "PG_VERSION";

    private ManifestIndex buildIndex(Path home) {
        Map<String, String> checksums = new TreeMap<>();
        // The index tracks source files only: skip the runtime scratch dir and the reserved
        // .tesseraql dir a packaged app carries build-generated artifacts in (e.g. docs/spec.json),
        // which are derived from the source and would otherwise make the index self-referential.
        // Also prune any persisted embedded-PostgreSQL data directory the user pointed inside the
        // app home (serve --embedded-db=<dir>): its files are non-deterministic runtime state, not
        // source, and on Windows the running postgres holds OS locks on them, so reading them to
        // hash would fail the load. We prune whole subtrees so the locked files are never read.
        Path work = home.resolve("work");
        Path generated = home.resolve(".tesseraql");
        try {
            Files.walkFileTree(home, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path normalized = dir.normalize();
                    if (normalized.equals(work) || normalized.equals(generated)
                            || Files.exists(dir.resolve(PG_DATA_MARKER))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (Files.isRegularFile(file)) {
                        checksums.put(relativeKey(home, file), sha256(file));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        StringBuilder aggregate = new StringBuilder();
        checksums.forEach(
                (key, value) -> aggregate.append(key).append(':').append(value).append('\n'));
        return ManifestIndex.of(checksums,
                sha256(aggregate.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static String relativeKey(Path home, Path file) {
        return home.relativize(file).toString().replace('\\', '/');
    }

    private void requireInside(Path home, Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(home)) {
            throw new TqlException(TRAVERSAL,
                    "Path escapes app home (design ch. 20.2): " + file);
        }
    }

    private static String sha256(Path file) {
        try {
            return sha256(Files.readAllBytes(file));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
