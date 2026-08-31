-- Replay fidelity (docs/idempotency-key.md decision 6), SQL Server variant: ADD takes the
-- column without a parenthesis, and varchar(max) is this table's large-text type.

alter table tql_idempotency_record add response_headers varchar(max);
