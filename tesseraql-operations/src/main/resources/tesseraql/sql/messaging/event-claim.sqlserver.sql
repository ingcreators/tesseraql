-- Claims deliverable messages for one consumer node (roadmap Phase 27), SQL Server variant:
-- UPDLOCK takes the row locks, READPAST skips rows other consumers hold - the SKIP LOCKED
-- equivalent - and TOP bounds the claim.
select top ( /* limit */ 100 ) event_id, channel, topic, msg_key, payload_json, attempts
from tql_event with (updlock, readpast, rowlock)
where channel = /* channel */ 'events'
  and topic = /* topic */ 'orders.created'
  and consumed_at is null
  and status <> 'DEAD'
  and (claimed_at is null or claimed_at < /* abandonedBefore */ '2026-01-01 00:00:00')
order by published_at
