-- Asynchronous scanning (docs/attachments.md): SQL Server variant of the shared V2.
alter table tql_attachment add scan_attempts int default 0 not null;

alter table tql_attachment add claimed_at datetime2;

alter table tql_attachment add scanned_at datetime2;
