-- TesseraQL SAML extension tables (design ch. 10.14), SQL Server variant.

if object_id('tql_saml_request', 'U') is null
create table tql_saml_request (
  request_id varchar(64) primary key,
  relay_state varchar(1000),
  created_at datetime2 not null
);

if object_id('tql_saml_seen_assertion', 'U') is null
create table tql_saml_seen_assertion (
  assertion_id varchar(256) primary key,
  expires_at datetime2 not null
);
