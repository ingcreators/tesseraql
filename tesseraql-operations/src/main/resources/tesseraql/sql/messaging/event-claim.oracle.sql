-- Claims deliverable messages for one consumer node (roadmap Phase 27), Oracle variant: ROWNUM
-- bounds the claim because Oracle does not combine FETCH FIRST with FOR UPDATE. ROWNUM is applied
-- before SKIP LOCKED takes effect, so a node may claim fewer rows than the limit while another node
-- holds locks - the next poll picks them up, preserving at-least-once delivery.
select event_id, channel, topic, msg_key, payload_json, attempts
from tql_event
where channel = /* channel */ 'events'
  and topic = /* topic */ 'orders.created'
  and consumed_at is null
  and status <> 'DEAD'
  and (claimed_at is null or claimed_at < /* abandonedBefore */ '2026-01-01 00:00:00')
  and rownum <= /* limit */ 100
for update skip locked
