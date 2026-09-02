package io.tesseraql.compiler.binding;

import io.tesseraql.yaml.i18n.MessageCatalog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The outcome report, as one display contract with more than one feeder
 * (docs/bulk-report.md decision 1, docs/csv-import.md decision 4). It renders through
 * {@code tql/view/report.html :: report(r)}: a summary, the failures that belong to no row, and
 * the rest grouped by reason — every group bounded, and the group list bounded too.
 *
 * <p>Two feeders fill it. A bulk action's browser leg turns its per-key outcomes into entries
 * labelled "Row 12 — PR-1003" and linked by row token. A reviewed import turns its parse
 * rejections into entries labelled by file line, linked at the preview row, and carrying the
 * column and the text that was refused. The shapes the second feeder needs are what this model
 * adds over the markup it replaces: {@code href} is a value each feeder supplies rather than a
 * {@code "#row-" + token} derivation, an entry carries {@link Entry#field} and
 * {@link Entry#value}, and a failure belonging to the file rather than to any row has a slot of
 * its own instead of a fabricated row number.
 *
 * <p>Grouping is by (code, message), not by code. A code with two distinct messages is two
 * reasons: one workflow guard means one sentence, but a parse pass emits {@code TQL-FIELD-…}
 * with a different sentence per column, and keying on the code alone merged them and dropped
 * every message after the first. That makes the number of groups data-dependent, so the group
 * list carries the same "…and N more" honesty the entries inside a group already had.
 *
 * <p>Both bounds belong to the feeder rather than to this class. They used to be one constant
 * applied when a report was <em>stored</em>, which no renderer could widen and no stored report
 * could recover from; a bulk action's convenience banner and a validation report the author has
 * to work through do not want the same number.
 *
 * @param id         the DOM id prefix the region and the group anchors are built from
 * @param variant    the {@code hc-alert} variant: {@code success}, {@code warning}, {@code error}
 * @param summary    the headline sentence, already resolved and interpolated by the feeder
 * @param fileErrors failures belonging to the file rather than to a row, or empty
 * @param entries    every failure, complete and in the order the feeder found them
 * @param groupCap   how many reason groups render
 * @param entryCap   how many entries render inside one group
 */
public record ReportModel(String id, String variant, String summary, List<String> fileErrors,
        List<Entry> entries, int groupCap, int entryCap) {

    public ReportModel {
        fileErrors = fileErrors == null ? List.of() : List.copyOf(fileErrors);
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /**
     * One failure the report names.
     *
     * @param code    the reason's own vocabulary — a guard id, a {@code TQL-*} code
     * @param message the reason's resolved sentence, or null to head the group with the code
     * @param label   how the entry refers to its row: "Row 12 — PR-1003", "Line 5"
     * @param href    where the entry navigates, or null when it names nothing reachable
     * @param field   the column the failure belongs to, when the feeder knows one
     * @param value   the text that was refused, when the feeder knows it
     */
    public record Entry(String code, String message, String label, String href, String field,
            String value) {
    }

    /**
     * The rendered model plus, for each entry in order, the DOM id of the group it landed in —
     * what a feeder needs to point a row's {@code aria-describedby} at its reason. An entry
     * whose group fell past {@link #groupCap} gets the region's own id: the row is still
     * marked, and what describes it is the report as a whole rather than a group that is not
     * on the page.
     */
    public record Rendered(Map<String, Object> model, List<String> groupIds) {
    }

    /** The region's DOM id — also the describedby of last resort. */
    public String regionId() {
        return id + "-report";
    }

    /** Builds the {@code r} model the fragment consumes. */
    public Rendered render(MessageCatalog catalog, Locale locale) {
        // Insertion-ordered: the feeder's order is the reading order, and a reviewed import
        // renders the same report twice (review, then commit) from the same entries.
        Map<List<String>, List<Entry>> grouped = new LinkedHashMap<>();
        for (Entry entry : entries) {
            grouped.computeIfAbsent(java.util.Arrays.asList(entry.code(), entry.message()),
                    key -> new ArrayList<>()).add(entry);
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        Map<List<String>, String> idsByKey = new LinkedHashMap<>();
        for (Map.Entry<List<String>, List<Entry>> reason : grouped.entrySet()) {
            if (groups.size() >= groupCap) {
                break;
            }
            String groupId = id + "-group-" + groups.size();
            idsByKey.put(reason.getKey(), groupId);
            groups.add(group(catalog, locale, groupId, reason.getKey(), reason.getValue()));
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("id", id);
        model.put("region", regionId());
        model.put("variant", variant);
        model.put("summary", summary);
        model.put("fileErrors", fileErrors.isEmpty() ? null : List.copyOf(fileErrors));
        model.put("groups", groups);
        int hiddenReasons = grouped.size() - groups.size();
        model.put("more", hiddenReasons == 0
                ? null
                : ViewMessages.text(catalog, locale, "tql.report.moreReasons",
                        "…and {count} more reason(s)", Map.of("count", hiddenReasons)));
        List<String> groupIds = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            groupIds.add(idsByKey.getOrDefault(
                    java.util.Arrays.asList(entry.code(), entry.message()), regionId()));
        }
        return new Rendered(model, groupIds);
    }

    /** One reason group: its heading with the true count, its bounded entries, its remainder. */
    private Map<String, Object> group(MessageCatalog catalog, Locale locale, String groupId,
            List<String> key, List<Entry> members) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("id", groupId);
        // The heading is the reason's DECLARED sentence when there is one; the code is the
        // honest fallback — the same vocabulary the JSON outcomes speak.
        String heading = key.get(1) != null ? key.get(1) : String.valueOf(key.get(0));
        group.put("heading", heading + " (" + members.size() + ")");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Entry member : members.subList(0, Math.min(entryCap, members.size()))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", member.label());
            row.put("href", member.href());
            row.put("field", member.field());
            row.put("value", member.value());
            rows.add(row);
        }
        group.put("rows", rows);
        long more = members.size() - (long) rows.size();
        group.put("more", more <= 0
                ? null
                : ViewMessages.text(catalog, locale, "tql.report.more", "…and {count} more",
                        Map.of("count", more)));
        return group;
    }
}
