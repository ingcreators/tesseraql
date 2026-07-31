-- The buyer declines: the proposal clears and the order returns to issued, where the
-- supplier may confirm as ordered or propose again.
update orders
set proposed_date = null,
    slip_days = null,
    last_action = 'date-declined',
    acted_by = /* audit.user */ 'someone'
where id = /* key */ 'ORD-0'
  and /*%scope quotes_scope */ (1=1)
