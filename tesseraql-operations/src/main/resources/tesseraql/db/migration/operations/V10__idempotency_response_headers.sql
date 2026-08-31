-- Replay fidelity (docs/idempotency-key.md decision 6): a replayed commit must carry the
-- headers that matter - the HX-Trigger toast, the PRG Location - so the complete step
-- snapshots an allowlisted set as JSON and the replay re-emits it.

alter table tql_idempotency_record add column response_headers text;
