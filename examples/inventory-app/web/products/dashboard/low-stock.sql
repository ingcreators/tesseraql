select sku, name, stock, reorder_level
from products
where stock <= reorder_level
order by stock
