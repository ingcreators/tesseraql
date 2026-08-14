package io.tesseraql.studio;

import io.tesseraql.yaml.SimpleYamlParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * What a form editor needs of the app around it: where it is, how to read a document the way the
 * editor reads it (a pending draft in preference to the saved source), how to write one back, and
 * who to tell.
 *
 * <p>The declaration directories all have the same shape (docs/app-layout.md) — a named
 * declaration under a top-level key, in one of several {@code *.yml} documents — so finding one
 * is the same walk whichever editor is asking.
 */
final class Declarations {

    /** A read that says where the text came from: a pending draft, or the saved source. */
    record Read(String text, boolean fromDraft) {
    }

    /**
     * A named declaration located in its declaring document: the app-relative file, whether the
     * text came from a pending draft, the parsed document tree, and the declaration's own node.
     */
    record Located(String path, boolean fromDraft, Map<String, Object> tree,
            Map<String, Object> node) {
    }

    private final SimpleYamlParser parser = new SimpleYamlParser();
    private final Supplier<Path> appHome;
    private final DraftStore drafts;
    private final boolean readOnly;
    private final StudioService.AuditRecorder audit;

    Declarations(Supplier<Path> appHome, DraftStore drafts, boolean readOnly,
            StudioService.AuditRecorder audit) {
        this.appHome = appHome;
        this.drafts = drafts;
        this.readOnly = readOnly;
        this.audit = audit;
    }

    Path appHome() {
        return appHome.get();
    }

    SimpleYamlParser parser() {
        return parser;
    }

    boolean readOnly() {
        return readOnly;
    }

    /** Records who changed what, for the trail the audit page reads. */
    void audit(String actor, String action, String target) {
        audit.record(actor, action, target);
    }

    /** Saves the edited document as a draft — an apply still needs a human. */
    Path saveDraft(String relativePath, String content) {
        return drafts.saveDraft(relativePath, content);
    }

    String readDraft(String relativePath) {
        return drafts.readDraft(relativePath);
    }

    /**
     * The draft-preferring read: a pending draft of the file if there is one, otherwise the saved
     * source — so a second edit sees the first. The text is null when the file has neither.
     */
    Read read(String relativePath) {
        String draft = drafts.readDraft(relativePath);
        return draft != null
                ? new Read(draft, true)
                : new Read(drafts.sourceIfExists(relativePath), false);
    }

    /**
     * Finds the {@code <directory>/*.yml} document declaring {@code name} under its top-level
     * {@code <topKey>:} map and parses it as a tree, preferring a pending draft of the file.
     * Returns null when no document declares the name.
     */
    Located locate(String directory, String topKey, String name) {
        Path dir = appHome.get().resolve(directory);
        if (name == null || name.isBlank() || !Files.isDirectory(dir)) {
            return null;
        }
        List<Path> files;
        try (Stream<Path> listed = Files.list(dir)) {
            files = listed.filter(file -> file.getFileName().toString().endsWith(".yml"))
                    .sorted().toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        for (Path file : files) {
            String relative = directory + "/" + file.getFileName();
            Read read = read(relative);
            if (read.text() == null) {
                continue;
            }
            Map<String, Object> tree = parser.parseTree(read.text());
            Map<String, Object> declared = StudioService.anyMap(tree.get(topKey));
            if (declared.get(name) instanceof Map) {
                return new Located(relative, read.fromDraft(), tree,
                        StudioService.anyMap(declared.get(name)));
            }
        }
        return null;
    }
}
