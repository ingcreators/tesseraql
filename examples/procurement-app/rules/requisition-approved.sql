-- A returned row is a violation: the named requisition exists but has not reached the
-- approved state (or does not exist at all), so it cannot source an RFQ yet.
select 'requisitionId' as field
where not exists (
  select 1 from tql_workflow_instance wi
  where wi.doc_type = 'requisition'
    and wi.doc_id = /* requisitionId */ 'REQ-1001'
    and wi.current_state = 'approved')
