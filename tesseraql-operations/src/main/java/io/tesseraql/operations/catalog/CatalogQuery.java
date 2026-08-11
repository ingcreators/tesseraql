package io.tesseraql.operations.catalog;

import io.tesseraql.core.dialect.Dialect;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.sql.SqlIdentifiers;
import io.tesseraql.yaml.model.CatalogSpec;
import java.util.ArrayList;
import java.util.List;

/**
 * The SELECT a {@code table:}-declared catalog loads with (docs/lookups.md, decision 13).
 *
 * <p>The declaration names a table and columns rather than carrying SQL, because that is what
 * lets a maintenance command say {@code invalidates: [区分マスタ]} without anything parsing SQL
 * to find out which catalogs a table feeds.
 *
 * <p>Every identifier is checked against the framework's identifier rule and quoted for the
 * dialect, so a name is taken verbatim — Japanese column names included — and a value can never
 * arrive as an identifier. The {@code where:} values are bound, never interpolated.
 */
final class CatalogQuery {

    /** TQL-FIELD-4618: a catalog names something that is not a legal SQL identifier. */
    private static final TqlErrorCode NOT_AN_IDENTIFIER = new TqlErrorCode(TqlDomain.FIELD, 4618);

    private CatalogQuery() {
    }

    /** The load statement: key, label, and the active flag when one is declared. */
    static String select(CatalogSpec spec, String dialect) {
        String quote = Dialect.fromId(dialect)
                .map(known -> known.capabilities().identifierQuote())
                .orElse("\"");
        StringBuilder sql = new StringBuilder("select ")
                .append(quoted(spec.key(), quote))
                .append(", ").append(quoted(spec.label(), quote));
        if (present(spec.active())) {
            sql.append(", ").append(quoted(spec.active(), quote));
        }
        sql.append(" from ").append(quoted(spec.table(), quote));
        List<String> conditions = new ArrayList<>();
        spec.where().keySet().forEach(column -> conditions.add(quoted(column, quote) + " = ?"));
        if (!conditions.isEmpty()) {
            sql.append(" where ").append(String.join(" and ", conditions));
        }
        if (present(spec.order())) {
            sql.append(" order by ").append(quoted(spec.order(), quote));
        } else {
            sql.append(" order by ").append(quoted(spec.key(), quote));
        }
        return sql.toString();
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String quoted(String identifier, String quote) {
        if (identifier == null || !SqlIdentifiers.isIdentifier(identifier)) {
            throw new TqlException(NOT_AN_IDENTIFIER, "Catalog identifier '" + identifier
                    + "' is not a legal table or column name");
        }
        return quote + identifier + quote;
    }
}
