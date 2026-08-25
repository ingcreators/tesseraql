-- Scheduled delivery (docs/notifications.md, "Scheduled delivery"): an outbox entry may name
-- the instant before which it must not be delivered, and the key a later command withdraws it
-- by while it is still undelivered. Both are properties of the row, written in the business
-- transaction that created it.

alter table tql_outbox_event add column not_before timestamp;
alter table tql_outbox_event add column cancel_key varchar(256);
