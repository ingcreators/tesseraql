-- The context conditions on every role this person holds (docs/access-governance.md
-- structural decision 8). Read once at sign-in and carried on the grant, because the
-- conditions are a property of the grant while the context is a property of the request:
-- the frozen principal holds the first and every request supplies the second.
--
-- Only held roles are read. A condition on a role nobody here holds decides nothing, and
-- reading the store's whole condition table at every sign-in to discard most of it would
-- cost more the larger the deployment grows.
select
  r.role_code       as role_code,
  c.condition_kind  as condition_kind,
  c.value           as value
from
  tql_role_conditions c
  join tql_roles r on r.role_id = c.role_id
where
  c.role_id in (
    select ur.role_id
    from tql_user_roles ur
    where ur.user_id = /* userId */ 'u1'
      and (ur.starts_at is null or ur.starts_at <= current_timestamp)
      and (ur.ends_at is null or ur.ends_at > current_timestamp)

    union

    select gr.role_id
    from tql_user_groups ug
      join tql_group_roles gr on gr.group_id = ug.group_id
    where ug.user_id = /* userId */ 'u1'
      and (ug.starts_at is null or ug.starts_at <= current_timestamp)
      and (ug.ends_at is null or ug.ends_at > current_timestamp)
  )
order by
  role_code, condition_kind, value
;
