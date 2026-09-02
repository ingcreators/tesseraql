-- The denominator a progress card needs (docs/csv-import.md decision 6), Oracle variant: the
-- bootstrap tolerates ORA-01430 on a re-run.

alter table tql_file_transfer add expected_rows number(19);
