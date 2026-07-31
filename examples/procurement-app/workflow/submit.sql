-- Stamps the lane the approvalRoute decision chose (decisions/approval-route.yml); the
-- approve/advance transitions guard on it, so the routing survives in the document.
update purchase_requisitions
set approval_route = /* decision.approvalRoute.route */ 'manager',
    last_action = 'submit',
    acted_by = /* audit.user */ 'someone'
where id = /* key */ 'REQ-0'
