-- TesseraQL OIDC relying-party state (roadmap Phase 25): one row per in-flight authorization
-- request, holding the anti-CSRF state, the id-token nonce, and the PKCE code_verifier. Each row is
-- consumed exactly once when its state comes back on the callback, so a replayed or forged callback
-- is rejected on any node sharing the database.

create table if not exists tql_oidc_state (
  state varchar(64) primary key,
  nonce varchar(64) not null,
  code_verifier varchar(128) not null,
  created_at timestamp not null
);
