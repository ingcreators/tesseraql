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
  role_name varchar(200) not null
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
