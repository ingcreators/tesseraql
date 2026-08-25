-- Scheduled delivery (docs/notifications.md, "Scheduled delivery"), Oracle variant: ADD takes
-- a parenthesized column, and TIMESTAMP/VARCHAR2 are this table's own types.

alter table tql_outbox_event add (not_before timestamp);
alter table tql_outbox_event add (cancel_key varchar2(256));
