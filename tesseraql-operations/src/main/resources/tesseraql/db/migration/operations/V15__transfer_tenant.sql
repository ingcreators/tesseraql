-- The tenant a transfer was resolved for (docs/multi-tenancy.md): in a per-tenant isolation
-- mode the export's extraction and the import's row statement run on that tenant's pool, and
-- the after: statement a first download fires — a later request than the export's — must run
-- on the same pool, so the transfer remembers which. Null for an untenanted transfer.

alter table tql_file_transfer add column tenant_id varchar(64);
