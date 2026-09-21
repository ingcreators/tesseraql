-- The quotation's lines (docs/procurement-documents-and-edi.md decision 5): the same row
-- reach as the portal's line list — quotes_scope on the quote header — with the extended
-- amount computed here, so the document and the comparison agree on one arithmetic.
select l.line_no, i.name as item_name, i.unit, l.qty, l.unit_price,
       cast(l.qty * l.unit_price as numeric(14, 2)) as amount, l.promised_date
from quote_lines l
join quotes c on c.id = l.quote_id
join items i on i.id = l.item_id
where l.quote_id = /* id */ 'Q-RFQ-2002-P-200'
  and /*%scope quotes_scope on c */ (1=1)
order by l.line_no
