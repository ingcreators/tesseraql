-- Records a decision on a pending request. Keyed on `pending`, so two approvers racing on
-- the same request produce one decision and one grant: the second affects zero rows and the
-- caller stops there rather than granting twice.
update tql_access_requests set
  status        = /* status */ 'approved',
  decided_by    = /* decidedBy */ 'kenji',
  decided_at    = /* decidedAt */ '2026-08-20 09:00:00',
  decision_note = /* decisionNote */ null,
  granted_until = /* grantedUntil */ null
where
  request_id = /* requestId */ 'rq-1'
  and status = 'pending'
;
