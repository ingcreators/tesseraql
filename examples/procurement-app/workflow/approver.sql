-- The first-stage assignee: the requesting department's manager, resolved from the
-- department master — swap the departments row, not this SQL, when a manager changes.
select d.manager_login as assignee
from departments d
join purchase_requisitions r on r.department = d.id
where r.id = /* key */ 'REQ-0'
