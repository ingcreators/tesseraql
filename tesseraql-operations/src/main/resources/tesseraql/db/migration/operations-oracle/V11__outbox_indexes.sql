-- Outbox delivery indexes (docs/notifications.md, "Scheduled delivery"), Oracle variant:
-- same statements as the common script; the bootstrap tolerates ORA-00955 on the re-run.

create index idx_tql_outbox_claim on tql_outbox_event (status, not_before, created_at);
create index idx_tql_outbox_cancel on tql_outbox_event (app_name, cancel_key);
