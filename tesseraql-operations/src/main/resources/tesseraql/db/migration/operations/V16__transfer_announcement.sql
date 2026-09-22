-- What a transfer's follow-up announces when it commits (docs/list-export.md, the after:
-- commit): the route's emit: topics and invalidates: tables as JSON arrays, and the tenant the
-- topics are scoped to — the requesting principal's. Recorded when the export starts, because a
-- download-timed after: statement runs on a later request that may know no route: the
-- operations console's fetch, which used to claim and run the statement and announce nothing.
-- Null on an import and on a job step's export, which announce from their own declarations.

alter table tql_file_transfer add column emit_json text;
alter table tql_file_transfer add column invalidates_json text;
alter table tql_file_transfer add column emit_tenant_id varchar(64);
