-- Replay fidelity (docs/idempotency-key.md decision 6), Oracle variant: ADD takes a
-- parenthesized column, and CLOB is this table's large-text type.

alter table tql_idempotency_record add (response_headers clob);
