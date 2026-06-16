-- Schema owned by the user-admin example app (design ch. 31-32: db/migration applies at mount).
-- A fresh `serve` self-bootstraps these tables and a little demo data, so the README walkthrough
-- (GET /api/users?q=sato) returns rows with no manual setup. The integration tests that mount this
-- app rely on this migration for the schema and replace the demo rows with their own fixtures.

create table users (
  id serial primary key,
  name varchar(200),
  status varchar(32) not null,
  created_at timestamp default now()
);

create table app_groups (
  id serial primary key,
  display_name varchar(200) not null
);

-- Demo data for local development and the README walkthrough.
insert into users (name, status) values
  ('sato', 'ACTIVE'),
  ('suzuki', 'ACTIVE'),
  ('tanaka', 'INACTIVE');

insert into app_groups (display_name) values ('engineers');
