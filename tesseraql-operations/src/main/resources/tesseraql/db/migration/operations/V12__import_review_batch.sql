-- Parked import batches (docs/csv-import.md decision 2): with `import.review: required` the
-- upload parses and validates without writing, and this row is what it parks — the confirm
-- token, who may spend it, the uploaded bytes, the resolved read spec the commit must re-parse
-- under, the report, and the single-shot claim.
--
-- Deliberately not the transfer row: a transfer's status is its job execution's, so parking one
-- would mean a non-terminal JobStatus the batch platform can never finish, on a row for an
-- import that never ran. A batch becomes a transfer only when it is committed.
--
-- report_json holds the bounded display AND the complete set of rejected row numbers: the
-- display is capped, the rejection index is not, because "commit exactly what was reviewed" is
-- only well defined if the server knows every row it excluded.
--
-- Both spool_id and spool_uri, because the temp stores do not agree on which one addresses the
-- bytes: the file store resolves the URI, while the database and blob stores look the id up as
-- a key. Keeping only one of them works on one store and silently fails on the others.

create table if not exists tql_import_batch (
  batch_id varchar(64) primary key,
  route_id varchar(256) not null,
  app_name varchar(256) not null,
  subject varchar(256) not null,
  format varchar(32) not null,
  spool_id varchar(64),
  spool_uri varchar(1000),
  read_spec_json text,
  contract_json text,
  report_json text,
  row_count bigint not null default 0,
  ready_count bigint not null default 0,
  rejected_count bigint not null default 0,
  status varchar(32) not null,
  transfer_id varchar(64),
  claimed_at timestamp,
  expires_at timestamp not null,
  created_at timestamp not null
);

-- The review sweep deletes by expiry, and supersession looks a subject's live batch up on every
-- upload, so neither may scan the table. The owner index deliberately stops at (app_name,
-- subject) rather than including route_id: a subject holds at most a handful of live batches, so
-- route_id filters for free in the predicate, and three varchar(256) columns would come to
-- exactly 3072 bytes under utf8mb4 — InnoDB's maximum key length to the byte, legal today and
-- broken by any future widening of any one of them.
create index idx_tql_import_batch_expiry on tql_import_batch (expires_at);
create index idx_tql_import_batch_owner on tql_import_batch (app_name, subject);
