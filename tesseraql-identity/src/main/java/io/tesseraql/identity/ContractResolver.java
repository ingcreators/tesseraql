package io.tesseraql.identity;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves an Identity SQL Contract name to its SQL source for a realm (design ch. 10.7.1).
 *
 * <p>For a {@code managed} realm the SQL comes from the bundled default identity pack on the
 * classpath; for a {@code sql} realm it comes from the app's {@code security/identity/<realm>/}
 * directory, letting an existing database be used by swapping SQL only.
 */
public final class ContractResolver {

    private static final TqlErrorCode MISSING_CONTRACT = new TqlErrorCode(TqlDomain.IAM, 1001);
    private static final String PACK_PATH = "/io/tesseraql/identity/pack/default/sql/";

    private final RealmConfig realm;

    public ContractResolver(RealmConfig realm) {
        this.realm = realm;
    }

    /** Returns the 2-way SQL source for a contract, or throws if it is not available. */
    public String resolve(String contract) {
        return realm.type() == RealmConfig.RealmType.MANAGED
                ? fromPack(contract)
                : fromSqlRoot(contract);
    }

    private String fromPack(String contract) {
        String resource = PACK_PATH + contract + ".sql";
        try (InputStream in = ContractResolver.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new TqlException(MISSING_CONTRACT,
                        "Default identity pack has no contract '" + contract + "'");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private String fromSqlRoot(String contract) {
        Path file = realm.sqlRoot().resolve(contract + ".sql");
        if (!Files.isRegularFile(file)) {
            throw new TqlException(MISSING_CONTRACT, "Realm '" + realm.id()
                    + "' is missing contract SQL: " + file);
        }
        try {
            return Files.readString(file);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
