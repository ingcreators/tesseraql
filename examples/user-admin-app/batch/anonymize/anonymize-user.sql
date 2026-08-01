-- Runs once per reader row on the writer connection; the reader's columns bind as row.*.
update users
set name = 'former-user-' || cast(cast(/* row.id */ '0' as bigint) as varchar)
where id = /* row.id */ 0
