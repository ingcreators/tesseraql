-- Outbox delivery indexes (docs/notifications.md, "Scheduled delivery"): scheduled delivery
-- turned the outbox from a drain-fast queue into a table that holds rows for days, so the
-- dispatcher's claim poll (status, then the not_before window, ordered by created_at) and a
-- withdrawal's (app_name, cancel_key) lookup stop scanning delivered history. Idempotency via
-- the bootstrap's tolerated duplicate-index errors, as the session V4 index does.

create index idx_tql_outbox_claim on tql_outbox_event (status, not_before, created_at);
create index idx_tql_outbox_cancel on tql_outbox_event (app_name, cancel_key);
