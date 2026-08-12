import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { test } from 'node:test';
import { AppNode, buildAppTree } from '../src/core/tree';

function makeDemoApp(): string {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'tql-tree-'));
  const write = (relative: string, content = '') => {
    const file = path.join(home, ...relative.split('/'));
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, content);
  };
  write('config/tesseraql.yml');
  write('web/get.yml');
  write('web/items/get.yml');
  write('web/items/list.view.yml');
  write('web/items/search.sql');
  write('consume/orders/project.yml');
  write('domains/catalog.yml');
  write('rules/inventory.yml');
  write('rules/validate-stock.sql');
  write('decisions/approval.yml');
  write('calendars/jp.yml');
  write('catalogs/codes.yml');
  write('catalogs/currency.sql');
  write('workflow/purchase_request.yml');
  write('workflow/approve.sql');
  write('db/migration/V1__create_items.sql');
  write('tests/smoke-test.yml');
  return home;
}

function section(tree: AppNode[], label: string): AppNode {
  const found = tree.find((node) => node.label === label);
  assert.ok(found, `section ${label}`);
  return found;
}

test('builds the app layout sections', () => {
  const tree = buildAppTree(makeDemoApp());
  assert.deepEqual(tree.map((node) => node.label),
      ['Routes', 'Views', 'Domains', 'Rules', 'Decisions', 'Calendars', 'Catalogs',
        'Workflows', 'Migrations', 'Tests']);
});

test('catalogs list their documents and the SQL a file: catalog reads', () => {
  // The .sql belongs to a file: catalog, whose shape a table: and equality filters cannot
  // express — hiding it would leave half the declaration unreachable from the tree.
  const catalogs = section(buildAppTree(makeDemoApp()), 'Catalogs');
  assert.deepEqual(catalogs.children.map((node) => node.label), ['codes.yml', 'currency.sql']);
});

test('routes group by kind and exclude views and SQL', () => {
  const tree = buildAppTree(makeDemoApp());
  const routes = section(tree, 'Routes');
  assert.deepEqual(routes.children.map((node) => node.label), ['web', 'consume']);
  const web = routes.children[0];
  assert.deepEqual(web.children.map((node) => node.label), ['items', 'get.yml']);
  const items = web.children[0];
  assert.deepEqual(items.children.map((node) => node.label), ['get.yml']);
});

test('views, migrations, and tests list their files relative to their roots', () => {
  const tree = buildAppTree(makeDemoApp());
  assert.deepEqual(section(tree, 'Views').children.map((node) => node.label),
      ['web/items/list.view.yml']);
  assert.deepEqual(section(tree, 'Migrations').children.map((node) => node.label),
      ['migration/V1__create_items.sql']);
  assert.deepEqual(section(tree, 'Tests').children.map((node) => node.label),
      ['smoke-test.yml']);
});

test('shared definitions list as Domains, Rules, and Decisions, rule SQL included', () => {
  const tree = buildAppTree(makeDemoApp());
  assert.deepEqual(section(tree, 'Domains').children.map((node) => node.label),
      ['catalog.yml']);
  assert.deepEqual(section(tree, 'Rules').children.map((node) => node.label),
      ['inventory.yml', 'validate-stock.sql']);
  assert.deepEqual(section(tree, 'Decisions').children.map((node) => node.label),
      ['approval.yml']);
  assert.deepEqual(section(tree, 'Calendars').children.map((node) => node.label),
      ['jp.yml']);
});

test('sections without content disappear', () => {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'tql-tree-'));
  fs.mkdirSync(path.join(home, 'config'), { recursive: true });
  fs.writeFileSync(path.join(home, 'config', 'tesseraql.yml'), '');
  assert.deepEqual(buildAppTree(home), []);
});
