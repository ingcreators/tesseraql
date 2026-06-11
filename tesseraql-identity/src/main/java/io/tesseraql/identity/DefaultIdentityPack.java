package io.tesseraql.identity;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Access to the bundled default identity pack (design ch. 10.12): the standard {@code tql_*} schema
 * and the managed-realm contract SQL.
 */
public final class DefaultIdentityPack {

    private static final TqlErrorCode MISSING = new TqlErrorCode(TqlDomain.IAM, 1010);

    private DefaultIdentityPack() {
    }

    /** Returns the standard IAM schema DDL for a dialect (e.g. {@code postgres}). */
    public static String schema(String dialect) {
        String resource = "/io/tesseraql/identity/pack/default/schema/" + dialect + ".sql";
        try (InputStream in = DefaultIdentityPack.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new TqlException(MISSING, "No managed schema for dialect '" + dialect + "'");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
