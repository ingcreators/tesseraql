-- The detail query: the URL path parameter {受注番号} binds here by its own name
-- (docs/identifiers.md).
select 受注番号, 顧客名, 状態, 地域, 金額
from 受注
where 受注番号 = /* 受注番号 */'J-1001'
