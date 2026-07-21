insert into app.price_summary (sku, best_price, suppliers)
select sku, min(price) as best_price, count(distinct supplier) as suppliers
from read_csv(/* ${scope.drops}/supplier-prices.csv */ 'data/drops/supplier-prices.csv')
group by sku
