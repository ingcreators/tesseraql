-- Managed document-number sequences (roadmap Phase 18), SQL Server variant: gapless
-- allocation under the sequence row's lock, riding the allocating command's transaction.

if object_id('tql_doc_sequence', 'U') is null
create table tql_doc_sequence (
  name varchar(128) primary key,
  next_value bigint not null
);
