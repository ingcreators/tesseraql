-- The nightly pricing job (batch/pricing) lands the supplier price summary here:
-- durable state stays on main, the analytics engine only computes it (docs/duckdb.md).
create table price_summary (
    sku varchar(64) primary key,
    best_price numeric(12, 2) not null,
    suppliers integer not null
);
