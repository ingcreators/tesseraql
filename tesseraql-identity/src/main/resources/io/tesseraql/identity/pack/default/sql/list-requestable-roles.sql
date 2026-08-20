-- What this person could ask for (docs/access-governance.md structural decision 6): roles
-- that have an owner and that they do not already hold. A role with no owner is not
-- requestable at all -- that is the deny-by-default answer to "who approves this", rather
-- than falling back to whoever happens to administer the store.
select
  r.role_code   as role_code,
  r.role_name   as role_name,
  r.application as application
from
  tql_roles r
where
  exists (select 1 from tql_role_owners o where o.role_id = r.role_id)
  and not exists (
    select 1 from tql_user_roles ur
    where ur.user_id = /* userId */ 'u1' and ur.role_id = r.role_id
      and (ur.starts_at is null or ur.starts_at <= current_timestamp)
      and (ur.ends_at is null or ur.ends_at > current_timestamp)
  )
  and not exists (
    select 1 from tql_user_groups ug
      join tql_group_roles gr on gr.group_id = ug.group_id
    where ug.user_id = /* userId */ 'u1' and gr.role_id = r.role_id
      and (ug.starts_at is null or ug.starts_at <= current_timestamp)
      and (ug.ends_at is null or ug.ends_at > current_timestamp)
  )
  and not exists (
    select 1 from tql_access_requests q
    where q.requester_id = /* userId */ 'u1' and q.role_code = r.role_code
      and q.status = 'pending'
  )
order by
  r.role_code
;
