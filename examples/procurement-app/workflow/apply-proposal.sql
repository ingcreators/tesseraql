-- Accepting a proposal (auto or by the buyer) moves the promised dates to the proposed
-- one and closes the negotiation row.
update orders
set last_action = 'date-accepted',
    acted_by = /* audit.user */ 'someone'
where id = /* key */ 'ORD-0'
  and /*%scope quotes_scope */ (1=1)
