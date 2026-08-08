-- The list query: bind name = column name = JSON key (docs/identifiers.md).
select 受注番号, 顧客名, 状態, 地域, 金額
from 受注
/*%if 顧客名 */
where 顧客名 like '%' || /* 顧客名 */'山田' || '%'
/*%end*/
order by 受注番号
