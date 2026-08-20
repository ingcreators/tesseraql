-- TesseraQL standard IAM schema for the managed realm (design ch. 10.3, SQL Server).

if object_id('tql_users', 'U') is null
create table tql_users (
  user_id      varchar(64) primary key,
  login_id     varchar(200) not null unique,
  display_name varchar(200) not null,
  email        varchar(320),
  status       varchar(32) not null,
  password_hash varchar(500),
  password_algo varchar(64),
  password_params varchar(500),
  tenant_id    varchar(64),
  version      bigint not null default 0
);

if object_id('tql_groups', 'U') is null
create table tql_groups (
  group_id   varchar(64) primary key,
  group_code varchar(200) not null unique,
  group_name varchar(200) not null,
  tenant_id  varchar(64)
);

if object_id('tql_roles', 'U') is null
create table tql_roles (
  role_id   varchar(64) primary key,
  role_code varchar(200) not null unique,
  role_name varchar(200) not null,
  application varchar(200),
  source      varchar(32) not null default 'admin'
);

if object_id('tql_permissions', 'U') is null
create table tql_permissions (
  permission_id   varchar(64) primary key,
  permission_code varchar(200) not null unique,
  permission_name varchar(200) not null
);

if object_id('tql_user_groups', 'U') is null
create table tql_user_groups (
  user_id  varchar(64) not null,
  group_id varchar(64) not null,
  primary key (user_id, group_id)
);

if object_id('tql_user_roles', 'U') is null
create table tql_user_roles (
  user_id varchar(64) not null,
  role_id varchar(64) not null,
  source  varchar(32) not null default 'admin',
  starts_at datetime2,
  ends_at   datetime2,
  primary key (user_id, role_id)
);

if object_id('tql_group_roles', 'U') is null
create table tql_group_roles (
  group_id varchar(64) not null,
  role_id  varchar(64) not null,
  primary key (group_id, role_id)
);

if object_id('tql_role_permissions', 'U') is null
create table tql_role_permissions (
  role_id       varchar(64) not null,
  permission_id varchar(64) not null,
  primary key (role_id, permission_id)
);

create table tql_user_permissions (
  user_id       varchar(64) not null,
  permission_id varchar(64) not null,
  starts_at datetime2,
  ends_at   datetime2,
  primary key (user_id, permission_id)
);

create table tql_user_attributes (
  user_id varchar(64) not null,
  name    varchar(200) not null,
  value   varchar(1000),
  primary key (user_id, name)
);

create table tql_role_rules (
  rule_id varchar(64) primary key,
  role_id varchar(64) not null,
  enabled smallint not null default 1
);

create table tql_role_rule_conditions (
  rule_id        varchar(64) not null,
  attribute_name varchar(200),
  match_kind     varchar(32) not null,
  value          varchar(1000)
);

create table tql_user_identities (
  user_id          varchar(64) not null,
  provider         varchar(255) not null,
  external_subject varchar(255) not null,
  primary key (provider, external_subject)
);

if object_id('tql_sod_constraints', 'U') is null
create table tql_sod_constraints (
  constraint_id   varchar(64) primary key,
  constraint_name varchar(200) not null,
  severity        varchar(16) not null,
  description     varchar(1000)
);

if object_id('tql_sod_constraint_roles', 'U') is null
create table tql_sod_constraint_roles (
  constraint_id varchar(64) not null,
  role_code     varchar(200) not null,
  primary key (constraint_id, role_code)
);

if object_id('tql_grant_history', 'U') is null
create table tql_grant_history (
  event_id        varchar(64) primary key,
  occurred_at     datetime2 not null,
  actor           varchar(200),
  subject_user_id varchar(64) not null,
  change_kind     varchar(32) not null,
  subject_code    varchar(200) not null,
  application     varchar(200),
  source          varchar(32) not null,
  starts_at       datetime2,
  ends_at         datetime2,
  reason          varchar(1000),
  correlation     varchar(64)
);
