-- Claims deliverable outbox events for one dispatcher node (design ch. 39.3): pending rows plus
-- SENDING rows whose claim aged past the abandoned timeout (the claiming node crashed
-- mid-delivery), optionally narrowed to the apps this runtime hosts - untagged legacy rows stay
-- claimable by anyone. Rows are locked with SKIP LOCKED so concurrent dispatcher nodes never
-- pick the same event.
select *
from tql_outbox_event
where
  (
    status = 'PENDING'
    or (status = 'SENDING' and claimed_at < /* abandonedBefore */ '2026-01-01 00:00:00')
  )
/*%if apps != null */
  and (app_name is null or app_name in /* apps */ ('demo-app'))
/*%end*/
order by created_at
limit /* limit */ 100
for update skip locked
