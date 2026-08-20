-- One campaign's items, with the login id beside each subject so a reviewer reads names
-- rather than opaque keys.
select
  i.review_id    as review_id,
  i.user_id      as user_id,
  u.login_id     as login_id,
  u.display_name as display_name,
  i.item_kind    as item_kind,
  i.subject_code as subject_code,
  i.source       as source,
  i.decision     as decision,
  i.decided_by   as decided_by,
  i.decided_at   as decided_at,
  i.note         as note
from
  tql_access_review_items i
  left join tql_users u on u.user_id = i.user_id
where
  i.review_id = /* reviewId */ 'rv-1'
order by
  u.login_id, i.user_id, i.item_kind, i.subject_code
;
