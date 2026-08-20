-- Records one reviewer's decision. Only an open campaign takes decisions: once it is
-- closed, its items are the record of what was certified and must not move.
update tql_access_review_items set
  decision   = /* decision */ 'keep',
  decided_by = /* decidedBy */ 'kenji',
  decided_at = /* decidedAt */ '2026-08-20 09:00:00',
  note       = /* note */ null
where
  review_id = /* reviewId */ 'rv-1'
  and user_id = /* userId */ 'u1'
  and item_kind = /* itemKind */ 'role'
  and subject_code = /* subjectCode */ 'orders.approver'
  and review_id in (select review_id from tql_access_reviews where status = 'open')
;
