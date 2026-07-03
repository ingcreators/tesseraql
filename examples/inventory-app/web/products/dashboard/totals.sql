select count(*) as products, coalesce(sum(stock), 0) as units
from products
