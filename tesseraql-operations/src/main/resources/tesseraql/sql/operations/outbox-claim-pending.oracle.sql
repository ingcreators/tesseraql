-- Claims deliverable outbox events for one dispatcher node (design ch. 39.3), Oracle variant:
-- ROWNUM bounds the claim because Oracle does not combine FETCH FIRST with FOR UPDATE. ROWNUM is
-- applied before SKIP LOCKED takes effect, so a node may claim fewer rows than the limit while
-- another node holds locks - the next poll picks them up, preserving at-least-once delivery.
select *
from tql_outbox_event
where
  (
    status = 'PENDING'
    or (status = 'SENDING' and claimed_at < /* abandonedBefore */ '2026-01-01 00:00:00')
  )
/*%if apps != null */
  and app_name in /* apps */ ('demo-app')
/*%end*/
  and rownum <= /* limit */ 100
for update skip locked
