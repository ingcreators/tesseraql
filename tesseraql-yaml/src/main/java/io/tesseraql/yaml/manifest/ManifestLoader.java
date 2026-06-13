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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
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
        Path home = appHome.toAbsolutePath().normalize();
        if (!Files.isDirectory(home)) {
            throw new TqlException(LOAD_ERROR, "App home is not a directory: " + home);
        }
        AppConfig config = loadConfig(home);
        List<RouteFile> routes = loadRoutes(home);
        List<JobFile> jobs = loadJobs(home);
        List<ToolFile> tools = new ArrayList<>();
        List<ResourceFile> resources = new ArrayList<>();
        loadMcp(home, tools, resources);
        ManifestIndex index = buildIndex(home);
        return new AppManifest(home, config, routes, jobs, tools, resources, index);
    }

    private AppConfig loadConfig(Path home) {
        // Sources are deep-merged in precedence order so an install overlay can override nested
        // keys (for example a single datasource) without replacing whole config sub-trees (ch. 32.6).
        Map<String, Object> merged = new HashMap<>();
        deepMerge(merged, parseTreeIfPresent(home.resolve("config/application.yml")));
        deepMerge(merged, parseTreeIfPresent(home.resolve("config/tesseraql.yml")));
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

    private List<RouteFile> loadRoutes(Path home) {
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
                    .forEach(file -> routes.add(toRouteFile(home, webRoot, file)));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return routes;
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
     * document becomes a {@link ResourceFile} (read-only context addressed by its {@code uri}),
     * and everything else a {@link ToolFile}. Both reuse the route model (recipe, input, sql,
     * security); the {@code description} is the model-facing hint read from the same document.
     */
    private void loadMcp(Path home, List<ToolFile> tools, List<ResourceFile> resources) {
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
                        RouteDefinition definition = parser.parseRoute(file);
                        Map<String, Object> tree = parser.parseTree(file);
                        String description = string(tree.get("description"));
                        if ("resource".equals(tree.get("kind"))) {
                            resources.add(new ResourceFile(file, definition, description,
                                    string(tree.get("uri")), string(tree.get("mimeType"))));
                        } else {
                            tools.add(new ToolFile(file, definition, description));
                        }
                    });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
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

    private ManifestIndex buildIndex(Path home) {
        Map<String, String> checksums = new TreeMap<>();
        try (Stream<Path> files = Files.walk(home)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> !p.normalize().startsWith(home.resolve("work")))
                    .forEach(file -> checksums.put(relativeKey(home, file), sha256(file)));
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
