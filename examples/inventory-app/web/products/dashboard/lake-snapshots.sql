select snapshot_id, strftime(snapshot_time, '%Y-%m-%d %H:%M:%S') as taken_at
from ducklake_snapshots('lake')
order by snapshot_id desc
limit 10
