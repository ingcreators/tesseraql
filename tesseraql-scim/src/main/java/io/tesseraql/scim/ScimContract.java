package io.tesseraql.scim;

/**
 * The SQL statements backing SCIM inbound provisioning (design ch. 10.15). Each is 2-way SQL that
 * binds SCIM attribute names and aliases its result columns to SCIM attributes (see
 * {@link ScimUserMapper}). {@code create} and {@code findById} return the affected/looked-up row;
 * {@code list} returns the page bound by {@code startIndex}/{@code count}.
 *
 * @param createSql         inserts a user and returns its row (e.g. {@code INSERT ... RETURNING})
 * @param findByIdSql       selects a single user by {@code id}
 * @param listSql           selects a page of users
 * @param replaceSql        replaces a user by {@code id} and returns its row
 * @param deleteSql         deletes a user by {@code id} and returns the deleted id
 * @param findByUserNameSql selects a single user by {@code userName} (for {@code eq} filters)
 * @param countSql          counts all users for accurate {@code totalResults}; null/blank falls back
 *                          to the page size
 */
public record ScimContract(String createSql, String findByIdSql, String listSql,
        String replaceSql, String deleteSql, String findByUserNameSql, String countSql) {
}
