-- The lane clearing is the transition's declared stamp (approval_route: null); this
-- command is the audit note only.
update purchase_requisitions
set last_action = 'rework', acted_by = /* audit.user */ 'someone'
where id = /* key */ 'REQ-0'
