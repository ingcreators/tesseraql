select p.id, p.sku, p.name, p.category, p.stock, p.reorder_level,
       (select min(price) from supplier_prices sp where sp.sku = p.sku) as best_price
from products p
/*%if q */
where lower(p.name) like lower('%' || /* q */ 'mouse' || '%')
   or lower(p.sku) = lower(/* q */ 'MS-230')
/*%end*/
order by p.name
