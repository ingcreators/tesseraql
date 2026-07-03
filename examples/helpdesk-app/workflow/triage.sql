update tickets set updated_at = now(), assignee = /* audit.user */ 'agent'
where id = /* key */ 'T-0'
