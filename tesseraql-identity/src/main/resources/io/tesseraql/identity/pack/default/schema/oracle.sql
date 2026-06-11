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
  role_name varchar2(200) not null
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
