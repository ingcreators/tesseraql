package io.tesseraql.yaml.view;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which column a grid is sorted by, and what each header should therefore say.
 *
 * <p>Every sortable grid in the framework agrees on one contract, because the hc-datagrid the
 * kit renders reads it: the header carries {@code aria-sort} — {@code ascending},
 * {@code descending} or {@code none} — and a link that flips the active column's direction while
 * starting any other column ascending. That rule was written out three times (the studio's route
 * catalog and schema tables, its audit trail, and a declared {@code view:} table), each copy free
 * to disagree with the others about a request that names no column or no direction.
 *
 * <p>A request that names a column the grid cannot sort by is treated as naming none, so a stale
 * or hand-edited link falls back to the grid's own default instead of sorting by a column the
 * page does not have.
 *
 * @param key        the column the rows are ordered by, or null when the grid is unsorted
 * @param descending whether that order is descending
 * @param columns    the sortable columns, in header order
 */
public record SortState(String key, boolean descending, List<String> columns) {

    /** Copies the column list, so a caller's mutable list cannot change the state later. */
    public SortState {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    /**
     * Reads the request's {@code sort} and {@code dir} against a grid's sortable columns.
     *
     * <p>A direction the request states is honored whichever column ends up active; only a
     * request that states none falls back to the grid's own default, which is how the audit trail
     * opens on its newest entries without saying {@code dir=desc} in every link.
     *
     * @param sort              the requested column, or null
     * @param dir               {@code asc}/{@code desc}, or null when the request states none
     * @param columns           the sortable columns, in header order
     * @param defaultKey        the column to sort by when the request names none, or null for an
     *                          unsorted grid
     * @param defaultDescending the direction the default column opens in
     */
    public static SortState of(String sort, String dir, List<String> columns, String defaultKey,
            boolean defaultDescending) {
        String key = sort != null && columns.contains(sort) ? sort : defaultKey;
        boolean descending = dir == null || dir.isBlank()
                ? defaultDescending && java.util.Objects.equals(key, defaultKey)
                : "desc".equalsIgnoreCase(dir);
        return new SortState(key, descending, columns);
    }

    /** The active direction as the request spells it: {@code asc} or {@code desc}. */
    public String direction() {
        return descending ? "desc" : "asc";
    }

    /** Whether this column is the one the rows are ordered by. */
    public boolean isActive(String column) {
        return column != null && column.equals(key);
    }

    /** What the header's {@code aria-sort} says: the kit draws the arrow from it. */
    public String ariaSort(String column) {
        if (!isActive(column)) {
            return "none";
        }
        return descending ? "descending" : "ascending";
    }

    /** The direction this column's header link asks for: the active one flips, the rest start up. */
    public String nextDirection(String column) {
        return isActive(column) && !descending ? "desc" : "asc";
    }

    /** One header link, with any query the page has to carry along appended verbatim. */
    public String href(String baseUrl, String column, String extraQuery) {
        return baseUrl + "?sort=" + column + "&dir=" + nextDirection(column)
                + (extraQuery == null ? "" : extraQuery);
    }

    /** Every column's {@code aria-sort}, in header order. */
    public Map<String, String> ariaSorts() {
        Map<String, String> byColumn = new LinkedHashMap<>();
        columns.forEach(column -> byColumn.put(column, ariaSort(column)));
        return byColumn;
    }

    /** Every column's header link, in header order. */
    public Map<String, String> hrefs(String baseUrl, String extraQuery) {
        Map<String, String> byColumn = new LinkedHashMap<>();
        columns.forEach(column -> byColumn.put(column, href(baseUrl, column, extraQuery)));
        return byColumn;
    }

    /** Puts the model entries every sortable grid template reads. */
    public void putInto(Map<String, Object> model, String baseUrl, String extraQuery) {
        model.put("sortKey", key);
        model.put("sortDir", direction());
        model.put("sortHref", hrefs(baseUrl, extraQuery));
        model.put("ariaSort", ariaSorts());
    }
}
