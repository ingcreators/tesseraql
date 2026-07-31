-- The quote-collection follow-up lands with whoever drafted the RFQ: chasing the
-- suppliers is the owner's job until the deadline hands it to the head.
select created_by as assignee from rfqs where id = /* key */ 'RFQ-0'
