-- TesseraQL standard IAM schema for the managed realm (design ch. 10.3, MySQL).

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
  tenant_id  varchar(64),
  external_id varchar(64)
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
  source   varchar(32) not null default 'admin',
  starts_at datetime,
  ends_at   datetime,
  primary key (user_id, group_id)
);

create table if not exists tql_user_roles (
  user_id varchar(64) not null,
  role_id varchar(64) not null,
  source  varchar(32) not null default 'admin',
  starts_at datetime,
  ends_at   datetime,
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
  starts_at datetime,
  ends_at   datetime,
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

create table if not exists tql_role_owners (
  role_id    varchar(64) not null,
  owner_kind varchar(16) not null,
  owner_ref  varchar(200) not null,
  primary key (role_id, owner_kind, owner_ref)
);

create table if not exists tql_access_requests (
  request_id        varchar(64) primary key,
  requested_at      datetime not null,
  requester_id      varchar(64) not null,
  role_code         varchar(200) not null,
  reason            varchar(1000),
  requested_minutes integer,
  status            varchar(16) not null,
  decided_by        varchar(200),
  decided_at        datetime,
  decision_note     varchar(1000),
  granted_until     datetime
);

create table if not exists tql_access_reviews (
  review_id   varchar(64) primary key,
  review_name varchar(200) not null,
  application varchar(200),
  opened_at   datetime not null,
  opened_by   varchar(200),
  closed_at   datetime,
  closed_by   varchar(200),
  status      varchar(16) not null
);

create table if not exists tql_access_review_items (
  review_id    varchar(64) not null,
  user_id      varchar(64) not null,
  item_kind    varchar(16) not null,
  subject_code varchar(200) not null,
  source       varchar(32),
  decision     varchar(16) not null,
  decided_by   varchar(200),
  decided_at   datetime,
  note         varchar(1000),
  primary key (review_id, user_id, item_kind, subject_code)
);

create table if not exists tql_role_eligibility (
  user_id           varchar(64) not null,
  role_id           varchar(64) not null,
  max_minutes       integer not null,
  requires_reason   smallint default 0 not null,
  requires_approval smallint default 0 not null,
  expires_at        datetime,
  primary key (user_id, role_id)
);

create table if not exists tql_sod_constraints (
  constraint_id   varchar(64) primary key,
  constraint_name varchar(200) not null,
  severity        varchar(16) not null,
  description     varchar(1000)
);

create table if not exists tql_sod_constraint_roles (
  constraint_id varchar(64) not null,
  role_code     varchar(200) not null,
  primary key (constraint_id, role_code)
);

create table if not exists tql_grant_history (
  event_id        varchar(64) primary key,
  occurred_at     datetime not null,
  actor           varchar(200),
  subject_user_id varchar(64) not null,
  change_kind     varchar(32) not null,
  subject_code    varchar(200) not null,
  application     varchar(200),
  source          varchar(32) not null,
  starts_at       datetime,
  ends_at         datetime,
  reason          varchar(1000),
  correlation     varchar(64)
);

create table if not exists tql_role_conditions (
  role_id        varchar(64) not null,
  condition_kind varchar(16) not null,
  value          varchar(200) not null,
  primary key (role_id, condition_kind, value)
);
