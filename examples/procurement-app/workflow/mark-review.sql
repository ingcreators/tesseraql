update orders
set last_action = 'date-review', acted_by = /* audit.user */ 'someone'
where id = /* key */ 'ORD-0'
  and /*%scope quotes_scope */ (1=1)
