-- Outbox tables created before multi-node dispatch existed lack the claim column (the V1 create
-- includes it, but "create table if not exists" skips existing tables). Applied by the runtime's
-- Flyway framework migrations, which only run on PostgreSQL.

alter table tql_outbox_event add column if not exists claimed_at timestamp;
