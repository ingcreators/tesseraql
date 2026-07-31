-- Stamps the lane the orderApproval decision chose from the document's own selection
-- facts (is_lowest, delta_pct — computed at creation, not client-asserted).
update orders
set approval_lane = /* decision.orderApproval.lane */ 'auto',
    last_action = 'submit',
    acted_by = /* audit.user */ 'someone'
where id = /* key */ 'ORD-0'
