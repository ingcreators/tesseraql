-- The lane clearing is the transition's declared stamp (approval_lane: null); this
-- command is the audit note only.
update orders
set last_action = 'rework', acted_by = /* audit.user */ 'someone'
where id = /* key */ 'ORD-0'
