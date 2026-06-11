-- Allocates the next value by taking the sequence row's lock; the lock is held until the
-- surrounding command transaction ends, which is what makes the sequence gapless.
update tql_doc_sequence
set next_value = next_value + 1
where name = /* name */'order-number'
