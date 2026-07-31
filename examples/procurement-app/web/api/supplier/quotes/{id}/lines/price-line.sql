-- Priced only while the quote is a draft in the caller's own partner scope.
update quote_lines
set unit_price = /* unitPrice */ 12000,
    promised_date = cast(/* promisedDate */ '2026-08-25' as date)
where quote_id = /* id */ 'Q-RFQ-2001-P-100'
  and line_no = /* lineNo */ 1
  and exists (
    select 1 from quotes c
    where c.id = quote_lines.quote_id
      and c.status = 'draft'
      and /*%scope quotes_scope on c */ (1=1))
