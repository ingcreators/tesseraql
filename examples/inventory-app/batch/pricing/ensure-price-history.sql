create table if not exists lake.price_history (
    sku varchar,
    best_price double,
    suppliers integer,
    loaded_at timestamp
)
