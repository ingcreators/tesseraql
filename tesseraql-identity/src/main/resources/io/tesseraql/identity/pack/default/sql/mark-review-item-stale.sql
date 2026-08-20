-- An item whose grant is already gone at close is recorded as stale rather than revoked:
-- the campaign says what it certified, and claiming to have removed something that was
-- not there would be a false entry in the record.
update tql_access_review_items set
  decision = 'stale'
where
  review_id = /* reviewId */ 'rv-1'
  and user_id = /* userId */ 'u1'
  and item_kind = /* itemKind */ 'role'
  and subject_code = /* subjectCode */ 'orders.approver'
;
