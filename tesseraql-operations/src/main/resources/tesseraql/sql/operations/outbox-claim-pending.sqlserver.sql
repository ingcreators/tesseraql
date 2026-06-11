-- Claims deliverable outbox events for one dispatcher node (design ch. 39.3), SQL Server
-- variant: UPDLOCK takes the row locks, READPAST skips rows other dispatchers hold - the
-- SKIP LOCKED equivalent - and TOP bounds the claim.
select top ( /* limit */ 100 ) *
from tql_outbox_event with (updlock, readpast, rowlock)
where
  (
    status = 'PENDING'
    -- FAILED rows retry on the next poll until the dispatcher dead-letters them (Phase 20).
    or status = 'FAILED'
    or (status = 'SENDING' and claimed_at < /* abandonedBefore */ '2026-01-01 00:00:00')
  )
/*%if apps != null */
  and app_name in /* apps */ ('demo-app')
/*%end*/
order by created_at
