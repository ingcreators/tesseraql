-- One supplier's price for one SKU. The upsert is what makes a feed safe to import twice:
-- re-uploading corrects prices instead of accumulating them.
insert into supplier_prices (sku, supplier, price, updated_at)
values ( /* sku */ 'WIDGET-1', /* supplier */ 'acme',
         cast( /* price */ '9.99' as numeric(12, 2)), now() )
on conflict (sku, supplier) do update
   set price = excluded.price, updated_at = excluded.updated_at
;
