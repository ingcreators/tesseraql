-- Everybody already violating a constraint (docs/access-governance.md structural decision
-- 2): a constraint added to a store where people hold both sides has violations on day
-- one, and they are reported rather than resolved -- revoking somebody's access is an
-- administrator's decision, made with the reason recorded.
--
-- A row is one person holding one constrained code, so a violation is any person with two
-- or more rows for the same constraint; the page groups them. Held means held by any path,
-- so the union mirrors find-roles-by-user-id: a direct assignment inside its window, or a
-- group's role.
select
  c.constraint_id   as constraint_id,
  c.constraint_name as constraint_name,
  c.severity        as severity,
  h.user_id         as user_id,
  u.login_id        as login_id,
  h.role_code       as role_code
from
  tql_sod_constraints c
  join tql_sod_constraint_roles cr on cr.constraint_id = c.constraint_id
  join (
    select ur.user_id as user_id, r.role_code as role_code
    from tql_user_roles ur
      join tql_roles r on r.role_id = ur.role_id
    where (ur.starts_at is null or ur.starts_at <= current_timestamp)
      and (ur.ends_at is null or ur.ends_at > current_timestamp)
    union
    select ug.user_id as user_id, r.role_code as role_code
    from tql_user_groups ug
      join tql_group_roles gr on gr.group_id = ug.group_id
      join tql_roles r on r.role_id = gr.role_id
  ) h on h.role_code = cr.role_code
  left join tql_users u on u.user_id = h.user_id
order by
  c.constraint_name, h.user_id, h.role_code
;
