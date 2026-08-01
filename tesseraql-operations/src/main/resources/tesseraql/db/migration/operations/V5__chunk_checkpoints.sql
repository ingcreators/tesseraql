-- The chunk step (docs/batch-platform.md track C): the checkpoint a rerun resumes from
-- (one per job/step/business date, cleared when the step completes), the skipped rows a
-- writer failure records instead of failing the run, and the per-step skipped count.

create table if not exists tql_job_checkpoint (
  job_id varchar(256) not null,
  step_id varchar(256) not null,
  business_date date not null,
  last_key varchar(512) not null,
  updated_at timestamp not null,
  primary key (job_id, step_id, business_date)
);

create table if not exists tql_job_skips (
  skip_id varchar(64) primary key,
  job_execution_id varchar(64) not null,
  step_id varchar(256) not null,
  row_key varchar(512),
  message varchar(2000),
  created_at timestamp not null
);

alter table tql_step_execution add column skipped_rows integer;
