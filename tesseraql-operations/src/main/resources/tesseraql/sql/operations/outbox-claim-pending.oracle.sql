-- Claims deliverable outbox events for one dispatcher node (design ch. 39.3), Oracle variant:
-- ROWNUM bounds the claim because Oracle does not combine FETCH FIRST with FOR UPDATE. ROWNUM is
-- applied before SKIP LOCKED takes effect, so a node may claim fewer rows than the limit while
-- another node holds locks - the next poll picks them up, preserving at-least-once delivery.
select *
from tql_outbox_event
where
  (
    status = 'PENDING'
    -- FAILED rows retry on the next poll until the dispatcher dead-letters them (Phase 20).
    -- CANCELLED is terminal like SENT: a withdrawn entry is never claimed again.
    or status = 'FAILED'
    or (status = 'SENDING' and claimed_at < /* abandonedBefore */ '2026-01-01 00:00:00')
  )
/*%if apps != null */
  and app_name in /* apps */ ('demo-app')
/*%end*/
  -- A scheduled entry waits for its instant; an unscheduled one has always been due.
  and (not_before is null or not_before <= /* now */ '2026-01-01 00:00:00')
  and rownum <= /* limit */ 100
for update skip locked
