-- Every campaign with how far through it is. The counts are what a reviewer's progress bar
-- and an auditor's "was this finished" question both read.
select
  v.review_id   as review_id,
  v.review_name as review_name,
  v.application as application,
  v.opened_at   as opened_at,
  v.opened_by   as opened_by,
  v.closed_at   as closed_at,
  v.closed_by   as closed_by,
  v.status      as status,
  (select count(*) from tql_access_review_items i
     where i.review_id = v.review_id) as item_count,
  (select count(*) from tql_access_review_items i
     where i.review_id = v.review_id and i.decision = 'pending') as pending_count
from
  tql_access_reviews v
order by
  v.opened_at desc, v.review_id
;
