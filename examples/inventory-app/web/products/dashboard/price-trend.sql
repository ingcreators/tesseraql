select strftime(loaded_at, '%m-%d %H:%M') as label, round(avg(best_price), 2) as avg_price
from lake.price_history
group by strftime(loaded_at, '%m-%d %H:%M')
order by label
