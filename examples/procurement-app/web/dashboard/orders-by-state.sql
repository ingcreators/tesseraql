select wi.current_state as label, count(*) as value
from tql_workflow_instance wi
where wi.doc_type = 'order'
group by wi.current_state
order by count(*) desc, wi.current_state
