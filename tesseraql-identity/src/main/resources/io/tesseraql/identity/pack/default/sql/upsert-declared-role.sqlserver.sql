-- Reconciles one declared application role. SQL Server variant.
merge into tql_roles as t
using (
  select
    /* roleId */ 'orders.approver' as role_id,
    /* roleCode */ 'orders.approver' as role_code,
    /* roleName */ 'Approver' as role_name,
    /* application */ 'orders' as application
) as s
on t.role_code = s.role_code
when matched then update set
  role_name = s.role_name,
  application = s.application,
  source = 'declared'
when not matched then insert (role_id, role_code, role_name, application, source)
  values (s.role_id, s.role_code, s.role_name, s.application, 'declared')
;
