-- The scope directive is the row authority (docs/approval-workflow.md "guards and
-- scopes"): a supplier can submit only their own partner's quote — another partner's id
-- updates nothing and the transition fails, deny-by-default.
update quotes
set submitted_at = now()
where id = /* key */ 'Q-0'
  and /*%scope quotes_scope */ (1=1)
