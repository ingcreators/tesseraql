package io.tesseraql.core.util;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Identifies the database vendor behind a datasource from its JDBC metadata. The identifiers
 * ({@code postgresql}, {@code mysql}, ...) name vendor-specific resource directories, such as the
 * {@code db/migration-<vendor>} Flyway locations layered over the common scripts (design ch. 42).
 */
public final class DatabaseVendors {

    private DatabaseVendors() {
    }

    /** The vendor identifier, or empty when the product is unknown or unreachable. */
    public static Optional<String> vendor(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName()
                    .toLowerCase(Locale.ROOT);
            if (product.contains("postgres")) {
                return Optional.of("postgresql");
            }
            if (product.contains("mariadb")) {
                return Optional.of("mariadb");
            }
            if (product.contains("mysql")) {
                return Optional.of("mysql");
            }
            if (product.contains("oracle")) {
                return Optional.of("oracle");
            }
            if (product.contains("microsoft sql server")) {
                return Optional.of("sqlserver");
            }
            if (product.contains("h2")) {
                return Optional.of("h2");
            }
            return Optional.empty();
        } catch (SQLException ex) {
            return Optional.empty();
        }
    }
}
