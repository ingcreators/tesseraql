-- Claims deliverable messages of one channel/topic for one consumer node (roadmap Phase 27):
-- unconsumed rows that are unclaimed or whose claim aged past the abandoned timeout (the claiming
-- node crashed mid-consume). Rows are locked with SKIP LOCKED so concurrent consumer nodes never
-- pick the same message. PostgreSQL and MySQL 8.0+ share this LIMIT + FOR UPDATE SKIP LOCKED form.
select event_id, channel, topic, msg_key, payload_json, attempts
from tql_event
where channel = /* channel */ 'events'
  and topic = /* topic */ 'orders.created'
  and consumed_at is null
  and status <> 'DEAD'
  and (claimed_at is null or claimed_at < /* abandonedBefore */ '2026-01-01 00:00:00')
order by published_at
limit /* limit */ 100
for update skip locked
