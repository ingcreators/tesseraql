-- Scheduled delivery (docs/notifications.md, "Scheduled delivery"), SQL Server variant: ADD
-- takes the column list without a per-column ALTER, and datetime2 is this table's timestamp
-- type.

alter table tql_outbox_event add not_before datetime2;
alter table tql_outbox_event add cancel_key nvarchar(256);
