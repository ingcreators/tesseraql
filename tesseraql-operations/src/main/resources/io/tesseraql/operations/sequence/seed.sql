-- Creates a sequence on first use: value 1 is allocated to the seeder, 2 is next.
insert into tql_doc_sequence (name, next_value)
values (/* name */'order-number', 2)
