-- The opt-in business-route audit log (roadmap Phase 45): who called what, when, with the
-- declared decision-relevant params. Applied idempotently by JdbcRouteAuditStore.ensureSchema.
create table if not exists tql_route_audit (
  audit_id varchar(64) primary key,
  app_name varchar(256) not null,
  route_id varchar(256) not null,
  http_method varchar(16) not null,
  url_path varchar(512) not null,
  actor varchar(256),
  tenant_id varchar(256),
  status integer,
  duration_ms bigint,
  params_json text,
  trace_id varchar(64),
  occurred_at timestamp not null
);
create index if not exists idx_tql_route_audit_time on tql_route_audit (occurred_at);
create index if not exists idx_tql_route_audit_route on tql_route_audit (app_name, route_id);
