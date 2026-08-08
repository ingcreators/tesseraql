import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { test } from 'node:test';
import {
  SHELL_MODES,
  WIDGETS,
  scanViewDocuments,
  viewCompletionAt,
  viewIdOf,
} from '../src/core/views';

function tempApp(): string {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'tql-ext-views-'));
}

function writeView(home: string, relative: string, content: string): void {
  const file = path.join(home, ...relative.split('/'));
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, content);
}

test('the id is the explicit top-level id:, else the filename stem', () => {
  assert.equal(viewIdOf('recent.view.yml', 'kind: view\nid: requests.recent\n'), 'requests.recent');
  assert.equal(viewIdOf('recent.view.yml', "id: 'requests.recent'\n"), 'requests.recent');
  assert.equal(viewIdOf('requests.recent.view.yml', 'kind: view\nrecipe: list\n'), 'requests.recent');
  // A nested id (a field, a panel) is not the document id.
  assert.equal(viewIdOf('items.view.yml', 'fields:\n  - id: sku\n'), 'items');
});

test('scanning finds view documents under web/ and templates/, first id wins', () => {
  const home = tempApp();
  writeView(home, 'web/requests/recent.view.yml', 'kind: view\nid: requests.recent\n');
  writeView(home, 'web/items/items.view.yml', 'kind: view\nrecipe: list\n');
  writeView(home, 'templates/shared.view.yml', 'kind: view\nrecipe: detail\n');
  // A duplicate id is the build's finding (TQL-VIEW-3315); completion offers it once.
  writeView(home, 'templates/zz.view.yml', 'kind: view\nid: items\n');
  // Outside the registry roots: not a view document.
  writeView(home, 'db/stray.view.yml', 'kind: view\nid: stray\n');
  const views = scanViewDocuments(home);
  assert.deepEqual(views.map((view) => view.id).sort(),
      ['items', 'requests.recent', 'shared']);
  assert.deepEqual(views.find((view) => view.id === 'requests.recent'),
      { id: 'requests.recent', source: 'web/requests/recent.view.yml' });
});

test('an app without registry roots scans to empty', () => {
  assert.deepEqual(scanViewDocuments(tempApp()), []);
});

test('view: values complete as view ids in block and flow form', () => {
  assert.deepEqual(viewCompletionAt('    view: ', 10), { kind: 'view-id' });
  assert.deepEqual(viewCompletionAt('    view: requests.rec', 22), { kind: 'view-id' });
  // A dashboard panel embedding a view (docs/view-composition.md wave 2b).
  const panel = '  - { type: view, view: req';
  assert.deepEqual(viewCompletionAt(panel, panel.length), { kind: 'view-id' });
  // A detail child referencing a view.
  const child = '  - { view: requests.history';
  assert.deepEqual(viewCompletionAt(child, child.length), { kind: 'view-id' });
  // A half-typed Japanese id keeps its context (docs/unicode-identifiers.md).
  assert.deepEqual(viewCompletionAt('    view: 受注', 12), { kind: 'view-id' });
});

test('views: list entries complete as view ids', () => {
  assert.deepEqual(viewCompletionAt('    views: [', 12), { kind: 'view-id' });
  const second = '    views: [requests.recent, requests.st';
  assert.deepEqual(viewCompletionAt(second, second.length), { kind: 'view-id' });
});

test('view-id contexts do not fire elsewhere', () => {
  assert.equal(viewCompletionAt('  preview: x', 12), undefined);
  assert.equal(viewCompletionAt('    view: items extra', 21), undefined);
  assert.equal(viewCompletionAt('  recipe: view', 14), undefined);
  assert.equal(viewCompletionAt('  columns: [a, b', 16), undefined);
});

test('shell: completes the negotiation vocabulary', () => {
  assert.deepEqual(viewCompletionAt('    shell: ', 11), { kind: 'shell' });
  assert.deepEqual(viewCompletionAt('    shell: al', 13), { kind: 'shell' });
  assert.equal(viewCompletionAt('  eggshell: a', 13), undefined);
  assert.deepEqual(SHELL_MODES.map((mode) => mode.name), ['auto', 'always', 'never']);
});

test('widget: completes the enum on view fields and domain fields', () => {
  assert.deepEqual(viewCompletionAt('    widget: ', 12), { kind: 'widget' });
  const flow = '  - { name: notes, widget: text';
  assert.deepEqual(viewCompletionAt(flow, flow.length), { kind: 'widget' });
  const domainField = '    sku: { type: string, widget: ';
  assert.deepEqual(viewCompletionAt(domainField, domainField.length), { kind: 'widget' });
  assert.deepEqual([...WIDGETS], [
    'text', 'textarea', 'number', 'date', 'datetime-local', 'checkbox', 'select', 'hidden',
  ]);
});
