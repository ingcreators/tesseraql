-- Outbox delivery indexes (docs/notifications.md, "Scheduled delivery"), SQL Server variant:
-- guarded so the script stays re-runnable, as the session V4 index does.

if not exists (select 1 from sys.indexes where name = 'idx_tql_outbox_claim') create index idx_tql_outbox_claim on tql_outbox_event (status, not_before, created_at);
if not exists (select 1 from sys.indexes where name = 'idx_tql_outbox_cancel') create index idx_tql_outbox_cancel on tql_outbox_event (app_name, cancel_key);
