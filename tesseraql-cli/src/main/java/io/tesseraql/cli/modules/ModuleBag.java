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
import java.util.List;

/**
 * The manifest a fetched bag carries (docs/module-channel.md decision 5): what was collected, on
 * whose declaration, with a checksum each.
 *
 * <p>The bag itself is a partial local Maven repository — resolved into, never assembled by
 * copying, so it has the poms and metadata an offline resolve checks. This file is the human- and
 * script-readable account of that directory: an operator carrying it to a disconnected machine can
 * see which applications it covers and which platform binaries it holds without reading a
 * repository tree.
 */
public final class ModuleBag {

    /** The manifest's file name at the bag's root. */
    public static final String FILE_NAME = "bag.json";

    private static final ObjectMapper MAPPER = io.tesseraql.yaml.JsonMappers.constrained()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** One collected artifact and the declaration that asked for it. */
    public record Entry(String source, String coordinate, String sha256) {
    }

    private final List<Entry> entries = new ArrayList<>();

    /** Records every artifact of one source (an application name, or a platform binary). */
    public void add(String source, List<ResolvedModule> resolved) {
        for (ResolvedModule module : resolved) {
            entries.add(new Entry(source, module.coordinate(), module.sha256()));
        }
    }

    /** Records one artifact resolved outside a module closure (the embedded-database binary). */
    public void add(String source, String coordinate, String sha256) {
        entries.add(new Entry(source, coordinate, sha256));
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** Writes {@code bag.json} at the bag's root, deterministically ordered. */
    public Path write(Path bag) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("bagVersion", 1);
        ArrayNode artifacts = root.putArray("artifacts");
        entries.stream()
                .sorted(Comparator.comparing(Entry::source).thenComparing(Entry::coordinate))
                .forEach(entry -> {
                    ObjectNode node = artifacts.addObject();
                    node.put("source", entry.source());
                    node.put("coordinate", entry.coordinate());
                    node.put("sha256", entry.sha256());
                });
        Path file = bag.resolve(FILE_NAME);
        try {
            Files.createDirectories(bag);
            Files.writeString(file, MAPPER.writeValueAsString(root) + "\n");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return file;
    }
}
