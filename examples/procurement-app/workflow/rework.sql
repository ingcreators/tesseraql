-- Back to the author: clear the stamped lane so the next submit re-evaluates the decision.
update purchase_requisitions
set approval_route = null,
    last_action = 'rework',
    acted_by = /* audit.user */ 'someone'
where id = /* key */ 'REQ-0'
