-- What a supplier price feed lands in (docs/csv-import.md): the reviewed upload at
-- /products/prices/import writes here, one row per supplier per SKU.
--
-- The key is the pair, so re-uploading a feed corrects prices instead of accumulating them —
-- which is what makes the import safe to run twice, and what the per-row upsert relies on.
create table supplier_prices (
    sku varchar(40) not null,
    supplier varchar(60) not null,
    price numeric(12, 2) not null,
    updated_at timestamp not null default now(),
    primary key (sku, supplier)
);
