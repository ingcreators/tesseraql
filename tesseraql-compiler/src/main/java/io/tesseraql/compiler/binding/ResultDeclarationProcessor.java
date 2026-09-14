package io.tesseraql.compiler.binding;

import io.tesseraql.core.files.ColumnValueException;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.yaml.app.DeclaredKinds;
import io.tesseraql.yaml.model.InputField;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies a binding's {@code result:} declaration to the rows it published
 * (docs/temporal-semantics.md T3): each declared column's text is parsed into its kind — a
 * JSON value, a date's or a wall clock's canonical text, a decimal — after the read seam gave
 * every column the kind the database declares and before anything reads the rows (an
 * enrichment, a response, a view). Undeclared columns pass through.
 *
 * <p>A value that will not parse fails the read ({@code TQL-SQL-2503}), naming the source,
 * the column, the row and the kind: silently passing the text through would flip a consumer's
 * {@code payload.sku} from a value to null with no signal.
 *
 * <p>A declared column the query never produced cannot be linted — the columns of a query
 * with conditional directives are not derivable — so its honest twin is here: a declared
 * column absent from every row of a non-empty result is logged once per route and source,
 * never silently ignored, never a 500.
 */
public final class ResultDeclarationProcessor implements Step {

    private static final System.Logger LOG = System.getLogger(
            ResultDeclarationProcessor.class.getName());

    /** The (route, source, column) triples already warned about, so a busy route logs once. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private final String routeId;
    private final String into;
    private final Map<String, InputField> declared;

    /**
     * @param routeId  the route, for the once-per-route warning
     * @param into     the context key of the result whose rows are declared ({@code main},
     *                 {@code steps.header})
     * @param declared the resolved declaration, column name to its field
     */
    public ResultDeclarationProcessor(String routeId, String into,
            Map<String, InputField> declared) {
        this.routeId = routeId;
        this.into = into;
        this.declared = Map.copyOf(declared);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> context = exchange.getProperty(TesseraqlProperties.CONTEXT, Map.of(),
                Map.class);
        Object targetRaw = context.get(into);
        if (!(targetRaw instanceof Map<?, ?> target)
                || !(((Map<String, Object>) target).get("rows") instanceof List<?> rowsRaw)) {
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) rowsRaw;
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> result = new LinkedHashMap<>((Map<String, Object>) target);
        result.put("rows", apply(routeId, into, declared, rows));
        context.put(into, result);
    }

    /**
     * The rows with every declared column parsed into its kind — copies, since a reader may
     * hand out rows it will not let anyone write to — the one application the route source
     * processor and the command step share.
     */
    public static List<Map<String, Object>> apply(String routeId, String source,
            Map<String, InputField> declared, List<Map<String, Object>> rows) {
        List<Map<String, Object>> parsed = new java.util.ArrayList<>(rows.size());
        Set<String> seen = new java.util.HashSet<>();
        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> row = new LinkedHashMap<>(rows.get(index));
            for (Map.Entry<String, InputField> entry : declared.entrySet()) {
                String column = entry.getKey();
                if (!row.containsKey(column)) {
                    continue;
                }
                seen.add(column);
                Object value = row.get(column);
                if (value == null) {
                    continue;
                }
                try {
                    row.put(column, DeclaredKinds.read(column, entry.getValue(), value));
                } catch (ColumnValueException unparseable) {
                    throw DeclaredKinds.unparseable(source, column, index,
                            entry.getValue().type(), unparseable);
                }
            }
            parsed.add(row);
        }
        if (!rows.isEmpty()) {
            for (String column : declared.keySet()) {
                if (!seen.contains(column)
                        && WARNED.add(routeId + "\n" + source + "\n" + column)) {
                    LOG.log(System.Logger.Level.WARNING,
                            "Route ''{0}'': result column ''{1}'' is declared on source ''{2}'' but"
                                    + " no row of the result carries it - the declaration is not"
                                    + " applied. Check the column''s name against the query.",
                            routeId, column, source);
                }
            }
        }
        return parsed;
    }
}
