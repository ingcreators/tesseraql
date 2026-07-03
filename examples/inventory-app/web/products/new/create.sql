insert into products (sku, name, category, stock, reorder_level)
values (/* sku */ 'XX-0', /* name */ 'Example', /* category */ 'other',
        /* stock */ 0, coalesce(/* reorder_level */ 10, 10))
