-- A batch run has an owner and a pulse (docs/audit-hardening.md Decision 6): the node that
-- started it, and a timer-driven heartbeat that says it is still there. Without them a replica
-- killed mid-run leaves a RUNNING row that findRunning treats as a live run forever, and
-- overlap: skip wedges for good.

alter table tql_job_execution add owner_node varchar2(200);

alter table tql_job_execution add heartbeat_at timestamp;
