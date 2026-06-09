package io.tesseraql.scim;

/**
 * The SQL statements backing SCIM inbound provisioning (design ch. 10.15). Each is 2-way SQL that
 * binds SCIM attribute names and aliases its result columns to SCIM attributes (see
 * {@link ScimUserMapper}). {@code create} and {@code findById} return the affected/looked-up row;
 * {@code list} returns the page bound by {@code startIndex}/{@code count}.
 *
 * @param createSql   inserts a user and returns its row (e.g. Postgres {@code INSERT ... RETURNING})
 * @param findByIdSql selects a single user by {@code id}
 * @param listSql     selects a page of users
 */
public record ScimContract(String createSql, String findByIdSql, String listSql) {
}
