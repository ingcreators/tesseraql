-- TesseraQL OIDC relying-party state (roadmap Phase 25), SQL Server variant.

if object_id('tql_oidc_state', 'U') is null
create table tql_oidc_state (
  state varchar(64) primary key,
  nonce varchar(64) not null,
  code_verifier varchar(128) not null,
  created_at datetime2 not null
);
