-- Records the partner directory's reported headcount for the run. 2-way SQL: the dummy values
-- after the bind comments keep the statement runnable in a plain SQL tool.
insert into directory_headcount (
  unit,
  total,
  recorded_at
) values (
  /* unit */ 'sales',
  /* total */ 0,
  current_timestamp
)
;
