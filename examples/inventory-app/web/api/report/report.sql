select category, sum(total) as total
from read_parquet(/* ${dataset.report} */ 'dummy.parquet')
group by category
order by total desc
