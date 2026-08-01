select category as label, sum(stock) as stock, sum(reorder_level) as reorder
from products
group by category
order by category
