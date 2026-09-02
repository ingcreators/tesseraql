-- The denominator a progress card needs (docs/csv-import.md decision 6): how many rows the run
-- expects to write, so "12,000 rows" can read as "12,000 of 30,000".
--
-- Only a reviewed import can fill it, and that is the point: the review already parsed the whole
-- file, so the count is a fact rather than an estimate. A one-shot import discovers its row count
-- by reaching the end of the file, so this stays null there and the card counts up without a
-- total — which is honest, where a guessed denominator would not be.

alter table tql_file_transfer add column expected_rows bigint;
