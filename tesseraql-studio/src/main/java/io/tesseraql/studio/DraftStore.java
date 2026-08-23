package io.tesseraql.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The draft filesystem store under {@code work/studio/drafts} (design ch. 16.7): saving, reading,
 * diff-base tracking, conflict detection, and promotion of draft edits — every other Studio concern
 * funnels its writes through this store's draft/apply flow. Also home to the app-home path
 * confinement guard ({@link #resolve}) all Studio file access shares (design ch. 20.2).
 *
 * <p>The app home is read through a supplier because {@link StudioService#reload()} reassigns it;
 * capturing the value would pin the store to a stale manifest.
 */
final class DraftStore {

    private static final TqlErrorCode TRAVERSAL = new TqlErrorCode(TqlDomain.STUDIO, 4002);
    private static final TqlErrorCode INVALID_DRAFT = new TqlErrorCode(TqlDomain.STUDIO, 4221);

    /** Compiles a draft before it is promoted ({@link StudioService#preview}). */
    @FunctionalInterface
    interface Compiler {
        StudioService.PreviewResult compile(String relativePath, String content);
    }

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final Supplier<Path> appHome;
    private final boolean readOnly;
    private final Compiler compiler;
    private final StudioService.AuditRecorder audit;

    DraftStore(Supplier<Path> appHome, boolean readOnly, Compiler compiler,
            StudioService.AuditRecorder audit) {
        this.appHome = appHome;
        this.readOnly = readOnly;
        this.compiler = compiler;
        this.audit = audit;
    }

    private Path appHome() {
        return appHome.get();
    }

    /** Resolves an app-relative path, refusing any escape from the app home (design ch. 20.2). */
    Path resolve(String relativePath) {
        return io.tesseraql.core.files.ConfinedPath.under(appHome()).resolve(relativePath)
                .orElseThrow(() -> new TqlException(TRAVERSAL,
                        "Path escapes app home: " + relativePath));
    }

    /**
     * Reads a source file by its app-relative path, or {@code null} when no such file exists — the
     * case of a draft for a not-yet-applied new file, where there is no source to compare against.
     */
    String sourceIfExists(String relativePath) {
        Path file = resolve(relativePath);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Saves a draft edit of {@code relativePath} under {@code work/studio/drafts} without touching
     * the source of truth (design ch. 16.7). Rejected in read-only mode.
     */
    Path saveDraft(String relativePath, String content) {
        if (readOnly) {
            throw new TqlException(StudioService.READ_ONLY,
                    "Studio is read-only; drafts are disabled");
        }
        // A browser normalizes a <textarea>'s newlines to CRLF on submit, so a draft saved from the
        // editor arrives with \r\n even when nothing was edited. Store LF so a no-op save matches the
        // (LF) source — otherwise every line reads as changed in the diff, and applying the draft
        // would silently rewrite the source's line endings.
        content = content == null ? "" : content.replace("\r\n", "\n").replace('\r', '\n');
        resolve(relativePath); // validate the target path before writing the draft
        Path draft = draftPath(relativePath);
        // The first save records the source the edit is based on, so a later apply can detect that
        // the source changed underneath it (concurrent-edit conflict, Studio backlog D5).
        boolean firstSave = !Files.isRegularFile(draft);
        try {
            Files.createDirectories(draft.getParent());
            Files.writeString(draft, content);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        if (firstSave) {
            writeBaseMeta(relativePath, sourceIfExists(relativePath));
        }
        return draft;
    }

    /** Reads a previously saved draft, or null if none exists. */
    String readDraft(String relativePath) {
        Path draft = draftPath(relativePath);
        if (!Files.isRegularFile(draft)) {
            return null;
        }
        try {
            return Files.readString(draft);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Discards a saved draft of {@code relativePath} without touching the source of truth, so an
     * edit can be abandoned and the editor falls back to the source. Rejected in read-only mode;
     * idempotent (a no-op when no draft exists).
     *
     * @return whether a draft was actually removed
     */
    boolean deleteDraft(String relativePath) {
        if (readOnly) {
            throw new TqlException(StudioService.READ_ONLY,
                    "Studio is read-only; drafts are disabled");
        }
        resolve(relativePath); // validate the target path before touching the draft
        try {
            boolean removed = Files.deleteIfExists(draftPath(relativePath));
            Files.deleteIfExists(draftMetaPath(relativePath));
            return removed;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Whether applying {@code relativePath}'s draft would overwrite a source that changed since the
     * draft was started (Studio backlog D5): the recorded base differs from the current source. False
     * when there is no draft or no recorded base (e.g. a draft from before base tracking).
     */
    boolean draftConflicts(String relativePath) {
        if (readDraft(relativePath) == null) {
            return false;
        }
        BaseMeta meta = readBaseMeta(relativePath);
        if (meta == null) {
            // A present-but-unreadable sidecar is treated as a conflict — concurrent-edit
            // detection must fail closed, not silently clobber another author's change; a
            // genuinely absent sidecar is a pre-tracking draft and stays non-conflicting.
            return Files.isRegularFile(draftMetaPath(relativePath));
        }
        return !java.util.Objects.equals(meta.base(), sourceIfExists(relativePath));
    }

    /**
     * Promotes a saved draft to the source of truth after validating it compiles (design ch. 16.7):
     * {@code force} overwrites a source that changed since the draft was started (Studio backlog
     * D5), and {@code actor} (the caller, for the audit trail, Studio backlog D6) is recorded once
     * the draft is promoted. Without {@code force}, a concurrent-edit conflict is rejected so the
     * draft cannot silently clobber another change (last-apply-wins). Rejected in read-only mode;
     * the draft is removed once applied.
     */
    Path applyDraft(String relativePath, boolean force, String actor) {
        if (readOnly) {
            throw new TqlException(StudioService.READ_ONLY,
                    "Studio is read-only; apply is disabled");
        }
        String draft = readDraft(relativePath);
        if (draft == null) {
            throw new TqlException(StudioService.NOT_FOUND,
                    "No draft to apply for: " + relativePath);
        }
        if (!force && draftConflicts(relativePath)) {
            throw new TqlException(StudioService.CONFLICT,
                    "The saved source changed since this draft was started;"
                            + " review the diff and re-apply to overwrite, or discard the draft.");
        }
        StudioService.PreviewResult preview = compiler.compile(relativePath, draft);
        if (!preview.valid()) {
            throw new TqlException(INVALID_DRAFT,
                    "Draft does not compile: " + preview.error());
        }
        Path target = resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, draft);
            Files.deleteIfExists(draftPath(relativePath));
            Files.deleteIfExists(draftMetaPath(relativePath));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        audit.record(actor, "apply", relativePath);
        return target;
    }

    /**
     * Every pending draft under {@code work/studio/drafts} (Studio backlog D5): the app-relative path
     * each one edits, whether it conflicts with a source that changed underneath it, and whether it is
     * a new file (no source yet). Sorted by path; the base sidecars are skipped.
     */
    List<StudioService.DraftSummary> drafts() {
        Path draftsDir = appHome().resolve("work/studio/drafts").normalize();
        if (!Files.isDirectory(draftsDir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(draftsDir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(file -> !file.getFileName().toString().endsWith(".meta"))
                    .map(file -> draftsDir.relativize(file).toString().replace('\\', '/'))
                    .sorted()
                    .map(path -> new StudioService.DraftSummary(path, draftConflicts(path),
                            sourceIfExists(path) == null))
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Applies every pending draft that does not conflict, recording each to the audit trail as
     * {@code actor} (Studio Drafts bulk actions). Conflicting drafts are left untouched (skipped) —
     * they need a manual diff review in the editor — and counted. {@code needsRestart} is true when
     * any applied draft created a not-yet-served route file. Callers reload routes afterwards.
     */
    StudioService.BulkApplyResult applyAllDrafts(String actor) {
        int applied = 0;
        int skipped = 0;
        boolean needsRestart = false;
        for (StudioService.DraftSummary draft : drafts()) {
            if (draft.conflict()) {
                skipped++;
                continue;
            }
            boolean isNew = draft.isNew();
            try {
                applyDraft(draft.path(), false, actor);
                applied++;
                needsRestart = needsRestart || isNew;
            } catch (RuntimeException ex) {
                // Best-effort: a draft that will not apply cleanly (e.g. a conflict that appeared
                // between the snapshot and here) is left for manual review, not fatal to the batch.
                skipped++;
            }
        }
        return new StudioService.BulkApplyResult(applied, skipped, needsRestart);
    }

    /** Discards every pending draft, returning how many were removed (Studio Drafts bulk actions). */
    int discardAllDrafts() {
        int discarded = 0;
        for (StudioService.DraftSummary draft : drafts()) {
            if (deleteDraft(draft.path())) {
                discarded++;
            }
        }
        return discarded;
    }

    private Path draftPath(String relativePath) {
        return io.tesseraql.core.files.ConfinedPath
                .under(appHome().resolve("work/studio/drafts")).resolve(relativePath)
                .orElseThrow(() -> new TqlException(TRAVERSAL,
                        "Draft path escapes drafts dir: " + relativePath));
    }

    /** The sidecar recording the source a draft is based on (Studio backlog D5). */
    private Path draftMetaPath(String relativePath) {
        Path draft = draftPath(relativePath);
        return draft.resolveSibling(draft.getFileName().toString() + ".meta");
    }

    /** Records the source content a draft is based on ({@code null} when the source did not exist). */
    private void writeBaseMeta(String relativePath, String base) {
        Path meta = draftMetaPath(relativePath);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("base", base);
        try {
            Files.createDirectories(meta.getParent());
            Files.writeString(meta, jsonMapper.writeValueAsString(data));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Reads the recorded base for a draft, or {@code null} when none was recorded. */
    private BaseMeta readBaseMeta(String relativePath) {
        Path meta = draftMetaPath(relativePath);
        if (!Files.isRegularFile(meta)) {
            return null;
        }
        try {
            JsonNode node = jsonMapper.readTree(Files.readString(meta));
            JsonNode base = node.get("base");
            return new BaseMeta(base == null || base.isNull() ? null : base.asText());
        } catch (IOException ex) {
            return null;
        }
    }

    /** The source a draft was based on ({@code base} is null when the source did not exist). */
    private record BaseMeta(String base) {
    }
}
