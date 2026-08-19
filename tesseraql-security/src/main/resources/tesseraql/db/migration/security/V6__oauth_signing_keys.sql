-- The authorization server's signing keys (docs/token-issuance.md decision 3): in the
-- framework datasource so every replica serves one JWKS. Base64-encoded PKCS#8 / X.509,
-- the same storage convention as the Ed25519 release keys. A retired key keeps its row —
-- it stays published until every access token it signed has expired.

create table if not exists tql_oauth_signing_key (
  kid varchar(64) primary key,
  private_key text not null,
  public_key text not null,
  created_at timestamp not null,
  retired_at timestamp
);
