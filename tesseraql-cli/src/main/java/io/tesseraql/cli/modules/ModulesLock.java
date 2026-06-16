package io.tesseraql.cli.modules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code modules.lock} file: the declared {@code tesseraql.modules} plus the exact resolved
 * closure (every {@code group:artifact:version} with its SHA-256), so resolution is reproducible,
 * offline-repeatable and supply-chain-checkable. JSON with deterministic ordering for byte-stable
 * diffs, alongside the app's other {@code *.json} sidecars. Committed to the app repository.
 */
public final class ModulesLock {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** One resolved artifact pinned by coordinate and checksum. */
    public record Artifact(String coordinate, String sha256) {
    }

    private final List<String> modules;
    private final List<Artifact> artifacts;

    public ModulesLock(List<String> modules, List<Artifact> artifacts) {
        this.modules = List.copyOf(modules);
        this.artifacts = List.copyOf(artifacts);
    }

    public List<String> modules() {
        return modules;
    }

    public List<Artifact> artifacts() {
        return artifacts;
    }

    /** Builds a lock from the declared coordinates and the resolved closure, deterministically. */
    public static ModulesLock from(List<ModuleCoordinate> declared, List<ResolvedModule> resolved) {
        List<String> mods = declared.stream().map(ModuleCoordinate::canonical).sorted().toList();
        List<Artifact> arts = resolved.stream()
                .map(module -> new Artifact(module.coordinate(), module.sha256()))
                .sorted(Comparator.comparing(Artifact::coordinate)).toList();
        return new ModulesLock(mods, arts);
    }

    /** Reads a lock file, or empty when it does not exist. */
    public static Optional<ModulesLock> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Files.readString(file));
            List<String> mods = new ArrayList<>();
            root.path("modules").forEach(node -> mods.add(node.asText()));
            List<Artifact> arts = new ArrayList<>();
            root.path("artifacts").forEach(node -> arts.add(
                    new Artifact(node.path("coordinate").asText(), node.path("sha256").asText())));
            return Optional.of(new ModulesLock(mods, arts));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Writes the lock as deterministic, byte-stable JSON. */
    public void write(Path file) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("lockfileVersion", 1);
        ArrayNode mods = root.putArray("modules");
        modules.forEach(mods::add);
        ArrayNode arts = root.putArray("artifacts");
        for (Artifact artifact : artifacts) {
            ObjectNode node = arts.addObject();
            node.put("coordinate", artifact.coordinate());
            node.put("sha256", artifact.sha256());
        }
        try {
            Files.writeString(file, MAPPER.writeValueAsString(root) + "\n");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Verifies a resolved closure against this lock; returns problem messages (empty = verified). */
    public List<String> verify(List<ResolvedModule> resolved) {
        Map<String, String> locked = new HashMap<>();
        artifacts.forEach(artifact -> locked.put(artifact.coordinate(), artifact.sha256()));
        List<String> problems = new ArrayList<>();
        for (ResolvedModule module : resolved) {
            String expected = locked.get(module.coordinate());
            if (expected == null) {
                problems.add(module.coordinate() + " resolved but absent from modules.lock");
            } else if (!expected.equals(module.sha256())) {
                problems.add(module.coordinate() + " checksum mismatch (lock " + expected
                        + ", resolved " + module.sha256() + ")");
            }
        }
        return problems;
    }
}
