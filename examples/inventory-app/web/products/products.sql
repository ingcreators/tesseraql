select id, sku, name, category, stock, reorder_level
from products
/*%if q */
where lower(name) like lower('%' || /* q */ 'mouse' || '%')
   or lower(sku) = lower(/* q */ 'MS-230')
/*%end*/
order by name
