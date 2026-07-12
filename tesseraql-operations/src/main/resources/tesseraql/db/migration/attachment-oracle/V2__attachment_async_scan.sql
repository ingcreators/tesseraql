-- Asynchronous scanning (docs/attachments.md): Oracle variant of the shared V2.
alter table tql_attachment add scan_attempts number(10) default 0 not null;

alter table tql_attachment add claimed_at timestamp;

alter table tql_attachment add scanned_at timestamp;
