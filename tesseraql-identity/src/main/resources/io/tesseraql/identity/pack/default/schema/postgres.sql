-- TesseraQL standard IAM schema for the managed realm (design ch. 10.3, PostgreSQL).

create table if not exists tql_users (
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

create table if not exists tql_groups (
  group_id   varchar(64) primary key,
  group_code varchar(200) not null unique,
  group_name varchar(200) not null,
  tenant_id  varchar(64)
);

create table if not exists tql_roles (
  role_id   varchar(64) primary key,
  role_code varchar(200) not null unique,
  role_name varchar(200) not null,
  application varchar(200),
  source      varchar(32) not null default 'admin'
);

create table if not exists tql_permissions (
  permission_id   varchar(64) primary key,
  permission_code varchar(200) not null unique,
  permission_name varchar(200) not null
);

create table if not exists tql_user_groups (
  user_id  varchar(64) not null,
  group_id varchar(64) not null,
  primary key (user_id, group_id)
);

create table if not exists tql_user_roles (
  user_id varchar(64) not null,
  role_id varchar(64) not null,
  source  varchar(32) not null default 'admin',
  starts_at timestamp,
  ends_at   timestamp,
  primary key (user_id, role_id)
);

create table if not exists tql_group_roles (
  group_id varchar(64) not null,
  role_id  varchar(64) not null,
  primary key (group_id, role_id)
);

create table if not exists tql_role_permissions (
  role_id       varchar(64) not null,
  permission_id varchar(64) not null,
  primary key (role_id, permission_id)
);

create table if not exists tql_user_permissions (
  user_id       varchar(64) not null,
  permission_id varchar(64) not null,
  starts_at timestamp,
  ends_at   timestamp,
  primary key (user_id, permission_id)
);

create table if not exists tql_user_attributes (
  user_id varchar(64) not null,
  name    varchar(200) not null,
  value   varchar(1000),
  primary key (user_id, name)
);

create table if not exists tql_role_rules (
  rule_id varchar(64) primary key,
  role_id varchar(64) not null,
  enabled smallint default 1 not null
);

create table if not exists tql_role_rule_conditions (
  rule_id        varchar(64) not null,
  attribute_name varchar(200),
  match_kind     varchar(32) not null,
  value          varchar(1000)
);

create table if not exists tql_user_identities (
  user_id          varchar(64) not null,
  provider         varchar(255) not null,
  external_subject varchar(255) not null,
  primary key (provider, external_subject)
);
