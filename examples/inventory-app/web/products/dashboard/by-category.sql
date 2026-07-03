select category as label, sum(stock) as value
from products
group by category
order by category
