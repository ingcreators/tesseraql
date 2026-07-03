update products
set stock = stock + /* delta */ 0, updated_at = now()
where sku = /* sku */ 'MS-230'
