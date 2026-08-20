package io.tesseraql.core.sql;

import io.tesseraql.core.dialect.SqlErrorKind;
import io.tesseraql.core.dialect.SqlErrors;
import java.io.Serial;
import java.sql.SQLException;

/**
 * A contract's SQL failed (docs/contract-sql-execution.md). It names the contract that failed and
 * carries the portable {@link SqlErrorKind} the driver's answer classifies to, so a caller can
 * react to the <em>meaning</em> of the failure instead of asking each database its own question.
 *
 * <p>It is a {@link SQLException} rather than a wrapper around one, and it repeats the cause's
 * SQLState and vendor code, because contract SQL failures already travel as SQLExceptions through
 * every caller of {@link ContractStatement}: an existing {@code catch (SQLException)} keeps
 * catching, and {@link SqlErrors} keeps classifying, whether it is handed this or the driver's own.
 */
public final class ContractSqlException extends SQLException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String contract;
    private final SqlErrorKind kind;

    ContractSqlException(String contract, SQLException cause) {
        super(cause.getMessage(), cause.getSQLState(), cause.getErrorCode(), cause);
        this.contract = contract;
        this.kind = SqlErrors.classify(cause);
    }

    /** The contract whose SQL failed, as the caller named it (e.g. {@code scim.users.create}). */
    public String contract() {
        return contract;
    }

    /** What the failure means, classified across dialects. */
    public SqlErrorKind kind() {
        return kind;
    }
}
