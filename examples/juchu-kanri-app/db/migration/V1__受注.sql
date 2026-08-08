-- Orders and the shipping-class rules, named in Japanese end-to-end
-- (docs/identifiers.md): unquoted Unicode identifiers work on every supported dialect.
create table 受注 (
  受注番号 varchar(20) primary key,
  顧客名 varchar(100) not null,
  状態 varchar(20) not null,
  地域 varchar(20) not null,
  金額 numeric(12, 2) not null,
  created_at timestamp not null default current_timestamp
);

insert into 受注 (受注番号, 顧客名, 状態, 地域, 金額) values
  ('J-1001', '山田商事', '受付済', '関東', 120000),
  ('J-1002', '佐藤物産', '出荷済', '北海道', 54000);

-- The 送料区分 decision's backing table (docs/decision-tables.md): NULL = wildcard.
create table 送料区分_rules (
  id bigint primary key,
  地域 varchar(20),
  priority int not null,
  送料 varchar(100) not null
);

insert into 送料区分_rules (id, 地域, priority, 送料) values
  (1, '北海道', 10, '1200'),
  (2, null, 100, '800');
