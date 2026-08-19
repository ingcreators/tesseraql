-- Reconciles one declared application role. MySQL variant.
insert into tql_roles (role_id, role_code, role_name, application, source)
values (
  /* roleId */ 'orders.approver',
  /* roleCode */ 'orders.approver',
  /* roleName */ 'Approver',
  /* application */ 'orders',
  'declared'
)
as fresh
on duplicate key update
  role_name = fresh.role_name,
  application = fresh.application,
  source = 'declared'
;
