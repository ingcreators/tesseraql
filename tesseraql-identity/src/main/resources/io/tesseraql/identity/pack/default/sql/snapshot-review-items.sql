-- Snapshots the grants in scope into a review's items (docs/access-governance.md
-- structural decision 5). The snapshot is the point: a campaign that read live grants would
-- ask reviewers about a moving target and could never answer "what did we certify in Q3".
--
-- Every role a person holds by any path, plus their direct permission grants, each landing
-- as one pending item. `source` records how the grant arrived, so a reviewer can see that a
-- role came from a rule or a group rather than from somebody's decision.
--
-- The permission arm carries a bare null application (a union takes the column's type from
-- the arms that have one, so no cast is needed and no dialect is excluded); an
-- application-scoped review reaches a direct permission through the code prefix instead,
-- which is the same classifier the rest of the model uses.
insert into tql_access_review_items (review_id, user_id, item_kind, subject_code, source,
                                     decision)
select
  /* reviewId */ 'rv-1',
  h.user_id,
  h.item_kind,
  h.subject_code,
  h.source,
  'pending'
from (
  select ur.user_id as user_id, 'role' as item_kind, r.role_code as subject_code,
         ur.source as source, r.application as application
  from tql_user_roles ur
    join tql_roles r on r.role_id = ur.role_id
  where (ur.starts_at is null or ur.starts_at <= current_timestamp)
    and (ur.ends_at is null or ur.ends_at > current_timestamp)
  union
  select ug.user_id as user_id, 'role' as item_kind, r.role_code as subject_code,
         'group' as source, r.application as application
  from tql_user_groups ug
    join tql_group_roles gr on gr.group_id = ug.group_id
    join tql_roles r on r.role_id = gr.role_id
  where (ug.starts_at is null or ug.starts_at <= current_timestamp)
    and (ug.ends_at is null or ug.ends_at > current_timestamp)
  union
  select up.user_id as user_id, 'permission' as item_kind, p.permission_code as subject_code,
         'admin' as source, null as application
  from tql_user_permissions up
    join tql_permissions p on p.permission_id = up.permission_id
  where (up.starts_at is null or up.starts_at <= current_timestamp)
    and (up.ends_at is null or up.ends_at > current_timestamp)
) h
where 1 = 1
/*%if application != null */
  and (h.application = /* application */ 'orders'
    or (h.item_kind = 'permission'
      and h.subject_code like concat(/* application */ 'orders', '.%')))
/*%end*/
;
