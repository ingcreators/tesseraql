-- Managed document-number sequences (roadmap Phase 18): gapless allocation under the
-- sequence row's lock, riding the allocating command's transaction.

create table if not exists tql_doc_sequence (
  name varchar(128) primary key,
  next_value bigint not null
);
