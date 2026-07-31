update orders
set approval_lane = null,
    last_action = 'rework',
    acted_by = /* audit.user */ 'someone'
where id = /* key */ 'ORD-0'
