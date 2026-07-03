-- The opt-in business-route audit log (roadmap Phase 45), SQL Server variant.
if object_id('tql_route_audit', 'U') is null
create table tql_route_audit (
  audit_id nvarchar(64) primary key,
  app_name nvarchar(256) not null,
  route_id nvarchar(256) not null,
  http_method nvarchar(16) not null,
  url_path nvarchar(450) not null,
  actor nvarchar(256),
  tenant_id nvarchar(256),
  status int,
  duration_ms bigint,
  params_json nvarchar(max),
  trace_id nvarchar(64),
  occurred_at datetime2 not null
);
if not exists (select 1 from sys.indexes where name = 'idx_tql_route_audit_time')
create index idx_tql_route_audit_time on tql_route_audit (occurred_at);
if not exists (select 1 from sys.indexes where name = 'idx_tql_route_audit_route')
create index idx_tql_route_audit_route on tql_route_audit (app_name, route_id);
