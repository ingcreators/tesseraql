-- A violation row = the adjustment would drive stock below zero.
select sku from products
where sku = /* sku */ 'MS-230'
  and stock + /* delta */ 0 < 0
