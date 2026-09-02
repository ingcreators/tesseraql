-- The denominator a progress card needs (docs/csv-import.md decision 6), SQL Server variant:
-- guarded so the script stays re-runnable, as V7's flag is.

if col_length('tql_file_transfer', 'expected_rows') is null
alter table tql_file_transfer add expected_rows bigint;
