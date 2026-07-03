-- The opt-in business-route audit log (roadmap Phase 45), Oracle (23+) variant.
create table if not exists tql_route_audit (
  audit_id varchar2(64) primary key,
  app_name varchar2(256) not null,
  route_id varchar2(256) not null,
  http_method varchar2(16) not null,
  url_path varchar2(512) not null,
  actor varchar2(256),
  tenant_id varchar2(256),
  status number(10),
  duration_ms number(19),
  params_json clob,
  trace_id varchar2(64),
  occurred_at timestamp not null
);
create index if not exists idx_tql_route_audit_time on tql_route_audit (occurred_at);
create index if not exists idx_tql_route_audit_route on tql_route_audit (app_name, route_id);
