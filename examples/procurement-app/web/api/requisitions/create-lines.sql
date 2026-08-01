-- The %for multi-row insert (docs/transactional-writes.md "Multi-row inserts"):
-- a variable-length detail insert stays one statement, runnable in a SQL tool.
insert into requisition_lines (requisition_id, line_no, item_id, qty, desired_date)
values
/*%for line : lines separator ', ' */
(/* requisitionId */ 'REQ-0', /* line_index */0 + 1, /* line.itemId */ 'IT-001',
 /* line.qty */ 1, cast(/* line.desiredDate */ '2026-09-01' as date))
/*%end*/
