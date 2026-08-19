-- The authorization server's signing keys (docs/token-issuance.md decision 3), Oracle (23+)
-- variant. The bootstrap tolerates ORA-00955 on the re-run, as in the earlier versions.

create table tql_oauth_signing_key (
  kid varchar2(64) primary key,
  private_key clob not null,
  public_key clob not null,
  created_at timestamp not null,
  retired_at timestamp
);
