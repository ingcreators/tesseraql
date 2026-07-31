-- The quote submit guard is a SQL guard file now (workflow/quote-priced.sql,
-- docs/workflow-expressiveness.md): the counters that existed only so an expression
-- guard could read them — and the pricing surface's obligation to maintain them — go.
alter table quotes drop column total_lines;
alter table quotes drop column priced_lines;
