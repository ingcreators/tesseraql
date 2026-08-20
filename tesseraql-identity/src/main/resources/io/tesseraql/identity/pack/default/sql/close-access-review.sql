update tql_access_reviews set
  status    = 'closed',
  closed_at = /* closedAt */ '2026-08-20 09:00:00',
  closed_by = /* closedBy */ 'kenji'
where
  review_id = /* reviewId */ 'rv-1'
  and status = 'open'
;
