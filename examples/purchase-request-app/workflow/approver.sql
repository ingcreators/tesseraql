-- The assignee resolver: the approvalRoute decision (decisions/approval.yml) already chose
-- the lane from the document's amount, so the resolver just binds its output. Swap the
-- decision's rows — or move them behind a table source — without touching this SQL.
select /* decision.approvalRoute.assignee */'approver-1' as assignee
