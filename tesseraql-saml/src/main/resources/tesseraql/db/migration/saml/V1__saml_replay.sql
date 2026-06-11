-- TesseraQL SAML extension tables (design ch. 10.14): pending SP-initiated request ids (each
-- consumed exactly once via InResponseTo) and the assertion replay cache.

create table if not exists tql_saml_request (
  request_id varchar(64) primary key,
  relay_state varchar(1000),
  created_at timestamp not null
);

create table if not exists tql_saml_seen_assertion (
  assertion_id varchar(256) primary key,
  expires_at timestamp not null
);
