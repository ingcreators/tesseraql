-- Reads the value just allocated by increment.sql inside the same transaction.
select next_value - 1 as value
from tql_doc_sequence
where name = /* name */'order-number'
