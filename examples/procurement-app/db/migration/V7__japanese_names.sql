-- Slice 7 (docs/procurement-demo.md): the demo speaks Japanese. Display names and
-- titles become Japanese-first — ids, columns, and the suite's assertions are
-- untouched, so this is a data pass, not a schema change.

update departments set name = '技術部' where id = 'engineering';
update departments set name = '営業部' where id = 'sales';
update departments set name = '調達部' where id = 'procurement';

update items set name = '開発用ワークステーション' where id = 'IT-001';
update items set name = '27インチモニター' where id = 'IT-002';
update items set name = '昇降式デスク' where id = 'OF-001';
update items set name = 'タスクチェア' where id = 'OF-002';
update items set name = '校正サービス(出張)' where id = 'SV-001';

update partners set name = '北商事株式会社' where id = 'P-100';
update partners set name = 'ミナミオフィスサプライ株式会社' where id = 'P-200';

update purchase_requisitions
set title = '新入社員用ワークステーション一式', budget_label = 'FY26 技術部 設備予算'
where id = 'REQ-1001';
update purchase_requisitions
set title = '営業フロア用タスクチェア', budget_label = 'FY26 営業部 施設予算'
where id = 'REQ-1002';

update rfqs set title = '営業フロア用タスクチェア 見積依頼' where id = 'RFQ-2001';
update rfqs set title = '新入社員用ワークステーション一式 見積依頼' where id = 'RFQ-2002';
