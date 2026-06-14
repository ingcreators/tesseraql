-- TesseraQL OIDC relying-party state (roadmap Phase 25), Oracle variant.

create table tql_oidc_state (
  state varchar2(64) primary key,
  nonce varchar2(64) not null,
  code_verifier varchar2(128) not null,
  created_at timestamp not null
);
