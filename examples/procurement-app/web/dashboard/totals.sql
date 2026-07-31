-- The chain at a glance: managed workflow state is just rows, so the dashboard is one
-- scan of tql_workflow_instance per document type plus the app tables.
select
  (select count(*) from purchase_requisitions) as requisitions,
  (select count(*) from tql_workflow_instance wi
   where wi.doc_type = 'rfq' and wi.current_state = 'issued') as open_rfqs,
  (select count(*) from quotes where status = 'submitted') as submitted_quotes,
  (select count(*) from shipments where received_at is null) as in_transit
