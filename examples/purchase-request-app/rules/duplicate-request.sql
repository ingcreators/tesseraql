-- A returned row is a violation (docs/validation-rule-sets.md): the caller has already
-- filed a request with this exact title. The requester comes from the ambient principal
-- bind, not from the bind contract.
select 'title' as field
from purchase_requests
where requested_by = /* principal.loginId */'someone'
  and title = /* title */'Example'
