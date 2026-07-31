select o.id, o.rfq_id, o.quote_id, o.partner_id, p.name as partner_name, o.total_amount,
       o.is_lowest, o.delta_pct, o.selection_reason, o.approval_lane, o.ordered_by,
       o.last_action
from orders o
join partners p on p.id = o.partner_id
order by o.id
