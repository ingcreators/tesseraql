-- tesseraql-scaffold-checksum: sha256:859369ab6b48aef7977430621e038b376f927e92d2907bc56d40b7d1201b9ad7
-- A returned row is a violation (docs/validation-rule-sets.md): the value is
-- already taken by another row. Shared by create (excludeId null) and update.
select 'name' as field
from items
where name = /* name */'sample'
/*%if excludeId != null */
  and id <> /* excludeId */0
/*%end*/
