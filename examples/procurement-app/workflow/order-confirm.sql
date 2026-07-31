-- The supplier's row authority is the scope: another partner's order updates nothing
-- and the transition fails (buyer-side roles match the apply-all arms).
update orders
set last_action = 'confirm', acted_by = /* audit.user */ 'someone'
where id = /* key */ 'ORD-0'
  and /*%scope quotes_scope */ (1=1)
