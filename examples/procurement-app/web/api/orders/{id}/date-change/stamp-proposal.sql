-- The slip is computed against the latest promised date on the order's lines — never
-- client-asserted — and only an issued order in the caller's partner scope takes one.
update orders o
set proposed_date = cast(/* proposedDate */ '2026-09-10' as date),
    slip_days = cast(/* proposedDate */ '2026-09-10' as date)
                - (select max(l.promised_date) from order_lines l where l.order_id = o.id)
where o.id = /* id */ 'ORD-0'
  and exists (
    select 1 from tql_workflow_instance wi
    where wi.doc_type = 'order' and wi.doc_id = o.id and wi.current_state = 'issued')
  and /*%scope quotes_scope on o */ (1=1)
