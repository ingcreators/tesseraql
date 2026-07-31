-- The submit guard as a set condition (docs/workflow-expressiveness.md): a row means the
-- quote has lines and none is unpriced. This replaces the total_lines/priced_lines
-- counters the pricing surface used to maintain just so an expression guard could read
-- them.
select 1
from quotes c
where c.id = /* key */ 'Q-0'
  and exists (select 1 from quote_lines l where l.quote_id = c.id)
  and not exists (
    select 1 from quote_lines l where l.quote_id = c.id and l.unit_price is null)
