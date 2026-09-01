-- The supplier search (docs/reference-lookup.md): the route's author owns what
-- "searching suppliers" means — here name or code, contains-match. The lookup contract
-- needs the referencing field's column (supplier_id), the code, and the label selected.
select id as supplier_id, supplier_code, name
from suppliers
where 1 = 1
/*%if q != null && q != "" */
  and (name like '%' || /* q */'North' || '%'
       or supplier_code like '%' || /* q */'S-1' || '%')
/*%end*/
order by name
