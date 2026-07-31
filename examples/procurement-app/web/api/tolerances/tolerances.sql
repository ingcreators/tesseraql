select id, slip_min, slip_max, action, priority
from delivery_tolerances
order by priority, id
