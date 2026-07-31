-- The lane is the engine's stamp now (order.yml stamp:); this command is the audit
-- note only.
update orders
set last_action = 'submit', acted_by = /* audit.user */ 'someone'
where id = /* key */ 'ORD-0'
