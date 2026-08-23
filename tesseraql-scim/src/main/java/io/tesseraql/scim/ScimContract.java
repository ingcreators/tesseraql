package io.tesseraql.scim;

/**
 * The SQL statements backing SCIM inbound provisioning (design ch. 10.15). Each is 2-way SQL that
 * binds SCIM attribute names and aliases its result columns to SCIM attributes (see
 * {@link ScimUserMapper}). {@code create} and {@code findById} return the affected/looked-up row;
 * {@code list} returns the page bound by {@code startIndex}/{@code count}.
 *
 * @param createSql         inserts a user as a plain write (no {@code RETURNING} — the assigned
 *                          id comes back through {@code keys})
 * @param findByIdSql       selects a single user by {@code id}
 * @param listSql           selects a page of users
 * @param replaceSql        replaces a user by {@code id}; zero affected rows is the 404
 * @param deleteSql         deletes a user by {@code id}; zero affected rows is the 404
 * @param findByUserNameSql selects a single user by {@code userName} (for {@code eq} filters)
 * @param countSql          counts all users for accurate {@code totalResults}; null/blank falls back
 *                          to the page size
 * @param keys              the columns the store assigns on create, as a command step declares
 *                          {@code sql.keys:} (docs/contract-sql-execution.md structural
 *                          decision 2); empty when the caller supplies the id itself
 */
public record ScimContract(String createSql, String findByIdSql, String listSql,
        String replaceSql, String deleteSql, String findByUserNameSql, String countSql,
        java.util.List<String> keys) {
}
