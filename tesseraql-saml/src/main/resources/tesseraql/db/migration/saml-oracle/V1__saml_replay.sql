-- TesseraQL SAML extension tables (design ch. 10.14), Oracle variant.

create table tql_saml_request (
  request_id varchar2(64) primary key,
  relay_state varchar2(1000),
  created_at timestamp not null
);

create table tql_saml_seen_assertion (
  assertion_id varchar2(256) primary key,
  expires_at timestamp not null
);
