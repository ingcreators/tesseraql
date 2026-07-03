-- The assignee resolver: a fixed approver keeps the starter self-contained; swap in a
-- SELECT over your org tables (or the managed org-unit closure) in a real app.
select 'approver-1' as assignee
