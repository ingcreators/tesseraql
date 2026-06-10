package io.tesseraql.scim;

/**
 * The SCIM Group provisioning SQL contract (design ch. 10.15): the 2-way SQL statements that back
 * inbound group management. Group identity and membership are kept separate so a group's members can
 * be added and removed independently of the group row.
 *
 * @param createSql       inserts a group and returns its row (with assigned id)
 * @param findByIdSql     selects a group by service-provider id
 * @param listSql         selects a page of groups ({@code startIndex}, {@code count})
 * @param replaceSql      updates a group's own attributes by id and returns the row (empty when absent)
 * @param deleteSql       deletes a group by id and returns the deleted row (empty when absent)
 * @param listMembersSql  selects the members of a group ({@code groupId})
 * @param addMemberSql    adds a member to a group ({@code groupId}, {@code memberId}); idempotent
 * @param removeMemberSql removes a member from a group ({@code groupId}, {@code memberId})
 * @param countSql        counts all groups for accurate {@code totalResults}; null/blank falls back
 *                        to the page size
 */
public record ScimGroupContract(String createSql, String findByIdSql, String listSql,
        String replaceSql, String deleteSql, String listMembersSql, String addMemberSql,
        String removeMemberSql, String countSql) {
}
