package io.tesseraql.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.studio.StudioService.AuditEntry;
import io.tesseraql.studio.StudioService.AuditPage;
import io.tesseraql.yaml.view.SortState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Who did what, when: the append-only {@code work/studio/audit/audit.jsonl} log every
 * source-writing Studio operation stamps, and the reads the trail page makes of it
 * (Studio backlog D6, platform-UX H5/I2/I3).
 *
 * <p>Extracted from {@code StudioService} — the write side was one method the whole service
 * called, and the read side four more that only the trail page uses.
 */
final class AuditTrail {

    /** The columns the trail can be ordered by, in header order. */
    static final List<String> SORT_COLUMNS = List.of("at", "actor", "action", "target");

    private final ObjectMapper jsonMapper = io.tesseraql.yaml.JsonMappers.constrained();
    private final Supplier<Path> appHome;

    AuditTrail(Supplier<Path> appHome) {
        this.appHome = appHome;
    }

    /** Appends one entry. An actor the caller could not name is recorded as {@code unknown}. */
    void record(String actor, String action, String target) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("at", Instant.now().toString());
        entry.put("actor", actor == null || actor.isBlank() ? "unknown" : actor);
        entry.put("action", action);
        entry.put("target", target);
        Path log = log();
        try {
            Files.createDirectories(log.getParent());
            Files.writeString(log, jsonMapper.writeValueAsString(entry) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * The newest {@code limit} entries matching {@code query}. The filter runs over the
     * <em>whole</em> log before the limit applies, so a search reaches older actions rather than
     * only the newest window; an empty query returns the newest {@code limit} entries.
     */
    List<AuditEntry> entries(int limit, String query) {
        List<AuditEntry> entries = filteredNewestFirst(query);
        return entries.size() > limit ? List.copyOf(entries.subList(0, limit)) : entries;
    }

    /**
     * One page of the trail: the whole log is filtered, then ordered, then sliced to the
     * {@code page}-th 1-based page of {@code size}. The filtered {@code total} comes back so the
     * view can render pagination.
     *
     * <p>The order comes from the same {@link SortState} the header links are rendered from, so
     * the arrow a reader sees and the rows they are looking at cannot disagree.
     */
    AuditPage page(String query, String sort, String dir, int page, int size) {
        int p = Math.max(1, page);
        List<AuditEntry> all = new ArrayList<>(filteredNewestFirst(query));
        // No stated column and no stated direction is the default newest-first (at desc).
        SortState state = SortState.of(sort, dir, SORT_COLUMNS, "at", true);
        Comparator<AuditEntry> cmp = comparator(state.key());
        all.sort(state.descending() ? cmp.reversed() : cmp);
        int total = all.size();
        int from = Math.min((p - 1) * size, total);
        int to = Math.min(from + size, total);
        return new AuditPage(List.copyOf(all.subList(from, to)), p, size, total);
    }

    private static Comparator<AuditEntry> comparator(String key) {
        return switch (key) {
            case "actor" -> Comparator.comparing(e -> e.actor().toLowerCase(Locale.ROOT));
            case "action" -> Comparator.comparing(e -> e.action().toLowerCase(Locale.ROOT));
            case "target" -> Comparator.comparing(e -> e.target().toLowerCase(Locale.ROOT));
            default -> Comparator.comparing(AuditEntry::at); // "at": ISO timestamps sort lexically
        };
    }

    /** Every entry matching {@code query} (whole log), newest first. */
    private List<AuditEntry> filteredNewestFirst(String query) {
        Path log = log();
        if (!Files.isRegularFile(log)) {
            return List.of();
        }
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<AuditEntry> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(log)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = jsonMapper.readTree(line);
                AuditEntry entry = new AuditEntry(node.path("at").asText(""),
                        node.path("actor").asText(""),
                        node.path("action").asText(""), node.path("target").asText(""));
                if (q.isEmpty() || matches(entry, q)) {
                    entries.add(entry);
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        java.util.Collections.reverse(entries);
        return entries;
    }

    private static boolean matches(AuditEntry entry, String lowerQuery) {
        return entry.actor().toLowerCase(Locale.ROOT).contains(lowerQuery)
                || entry.action().toLowerCase(Locale.ROOT).contains(lowerQuery)
                || entry.target().toLowerCase(Locale.ROOT).contains(lowerQuery)
                || entry.at().toLowerCase(Locale.ROOT).contains(lowerQuery);
    }

    private Path log() {
        return appHome.get().resolve("work/studio/audit/audit.jsonl").normalize();
    }
}
