-- TesseraQL standard IAM schema for the managed realm (design ch. 10.3, Oracle 23+).

create table tql_users (
  user_id      varchar2(64) primary key,
  login_id     varchar2(200) not null unique,
  display_name varchar2(200) not null,
  email        varchar2(320),
  status       varchar2(32) not null,
  password_hash varchar2(500),
  password_algo varchar2(64),
  password_params varchar2(500),
  tenant_id    varchar2(64),
  version      number(19) default 0 not null
);

create table tql_groups (
  group_id   varchar2(64) primary key,
  group_code varchar2(200) not null unique,
  group_name varchar2(200) not null,
  tenant_id  varchar2(64)
);

create table tql_roles (
  role_id   varchar2(64) primary key,
  role_code varchar2(200) not null unique,
  role_name varchar2(200) not null,
  application varchar2(200),
  source      varchar2(32) default 'admin' not null
);

create table tql_permissions (
  permission_id   varchar2(64) primary key,
  permission_code varchar2(200) not null unique,
  permission_name varchar2(200) not null
);

create table tql_user_groups (
  user_id  varchar2(64) not null,
  group_id varchar2(64) not null,
  primary key (user_id, group_id)
);

create table tql_user_roles (
  user_id varchar2(64) not null,
  role_id varchar2(64) not null,
  source  varchar2(32) default 'admin' not null,
  starts_at timestamp,
  ends_at   timestamp,
  primary key (user_id, role_id)
);

create table tql_group_roles (
  group_id varchar2(64) not null,
  role_id  varchar2(64) not null,
  primary key (group_id, role_id)
);

create table tql_role_permissions (
  role_id       varchar2(64) not null,
  permission_id varchar2(64) not null,
  primary key (role_id, permission_id)
);

create table tql_user_permissions (
  user_id       varchar2(64) not null,
  permission_id varchar2(64) not null,
  starts_at timestamp,
  ends_at   timestamp,
  primary key (user_id, permission_id)
);

create table tql_user_attributes (
  user_id varchar2(64) not null,
  name    varchar2(200) not null,
  value   varchar2(1000),
  primary key (user_id, name)
);

create table tql_role_rules (
  rule_id varchar2(64) primary key,
  role_id varchar2(64) not null,
  enabled number(1) default 1 not null
);

create table tql_role_rule_conditions (
  rule_id        varchar2(64) not null,
  attribute_name varchar2(200),
  match_kind     varchar2(32) not null,
  value          varchar2(1000)
);

create table tql_user_identities (
  user_id          varchar2(64) not null,
  provider         varchar2(255) not null,
  external_subject varchar2(255) not null,
  primary key (provider, external_subject)
);
