-- Who started a transfer (docs/job-inbox.md decision 1): the requesting principal's stable
-- subject — the identity the reviewed import's commit already compares on its batch — recorded
-- when the transfer starts, on both directions. Null for a transfer nobody started: a polled
-- import, a job step's export, a public route's caller; never the empty string, because the
-- column is an owner to list by, not a key to compare.
--
-- An owner for listing, not a reader gate: the {transferId} subtree keeps answering for the
-- route, the app and the tenant (docs/edge-hygiene.md E0), whoever holds the link.
--
-- The owner index serves the two reads that follow — a subject's transfers of one application,
-- newest first — and stays under InnoDB's key ceiling that V12's three varchar(256) columns
-- would meet exactly: two of them and a timestamp do not.

alter table tql_file_transfer add column subject varchar(256);
create index idx_tql_file_transfer_owner on tql_file_transfer (app_name, subject, created_at);
