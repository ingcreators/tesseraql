insert into lake.price_history
select sku, min(price) as best_price, count(distinct supplier) as suppliers, now()
from read_csv(/* ${scope.drops}/supplier-prices.csv */ 'data/drops/supplier-prices.csv')
group by sku
