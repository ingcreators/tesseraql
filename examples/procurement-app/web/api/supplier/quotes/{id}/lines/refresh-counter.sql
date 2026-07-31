update quotes
set priced_lines = (select count(*) from quote_lines l
                    where l.quote_id = quotes.id and l.unit_price is not null)
where id = /* id */ 'Q-RFQ-2001-P-100'
  and /*%scope quotes_scope */ (1=1)
