select cast(/* seconds */ 15 as integer) as slept_seconds
from pg_sleep(/* seconds */ 15)
;
