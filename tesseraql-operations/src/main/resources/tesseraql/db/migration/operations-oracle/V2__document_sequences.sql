-- Managed document-number sequences (roadmap Phase 18), Oracle (23+) variant: gapless
-- allocation under the sequence row's lock, riding the allocating command's transaction.

create table tql_doc_sequence (
  name varchar2(128) primary key,
  next_value number(19) not null
);
