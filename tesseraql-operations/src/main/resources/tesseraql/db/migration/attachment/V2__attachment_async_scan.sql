-- Asynchronous scanning (docs/attachments.md): pending rows are claimed by the sweep
-- (claimed_at is the lease), retried up to the cap (scan_attempts), and stamped when the
-- verdict lands (scanned_at). Applied idempotently via SqlScripts' already-exists tolerance.
alter table tql_attachment add scan_attempts int default 0 not null;

alter table tql_attachment add claimed_at timestamp;

alter table tql_attachment add scanned_at timestamp;
