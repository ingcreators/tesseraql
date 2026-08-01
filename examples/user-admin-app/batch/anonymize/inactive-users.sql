-- The chunk reader contract (docs/jobs.md "The chunk step"): keyset-ordered, and it
-- resumes after the checkpointed key on a rerun. Checkpoint values bind as strings,
-- so a numeric key casts its bind.
select id
from users
where status = 'INACTIVE'
/*%if chunk.after != null */
  and id > cast(/* chunk.after */ '0' as bigint)
/*%end*/
order by id
