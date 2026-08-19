-- Reconciles one declared application role (docs/application-roles.md slice 3): insert or
-- update by code, always stamping source 'declared' (a re-declared orphan revives).
-- PostgreSQL syntax; other dialects override with a <dialect>.sql variant.
insert into tql_roles (role_id, role_code, role_name, application, source)
values (
  /* roleId */ 'orders.approver',
  /* roleCode */ 'orders.approver',
  /* roleName */ 'Approver',
  /* application */ 'orders',
  'declared'
)
on conflict (role_code) do update set
  role_name = excluded.role_name,
  application = excluded.application,
  source = 'declared'
;
