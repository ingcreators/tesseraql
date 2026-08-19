select count(*) as matched
from tql_org_closure
where ancestor_id = /* ancestorId */ 'root'
  and descendant_id = /* descendantId */ 'leaf'
;
