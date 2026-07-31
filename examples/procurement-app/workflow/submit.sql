-- The lane is the engine's stamp now (requisition.yml stamp:); this command is the
-- audit note only.
update purchase_requisitions
set last_action = 'submit', acted_by = /* audit.user */ 'someone'
where id = /* key */ 'REQ-0'
