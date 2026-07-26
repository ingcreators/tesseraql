import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  completionKindAt,
  parseAppSymbols,
  routeDescription,
  symbolReferenceAt,
  SymbolsContractError,
} from '../src/core/symbols';

test('parses the symbols document', () => {
  const symbols = parseAppSymbols(JSON.stringify({
    policies: [{ name: 'app.read', source: 'config/tesseraql.yml', line: 72 }],
    messages: [{ key: 'users.list.title', source: 'messages/en.yml', line: 3 }],
    domains: [{ name: 'sku', source: 'domains/catalog.yml', line: 3 }],
    rules: [{ name: 'editableStatus', source: 'rules/inventory.yml', line: 7 }],
    routes: [{ id: 'app.home', source: 'web/get.yml', method: 'GET', path: '/', recipe: 'query-html' }],
  }));
  assert.deepEqual(symbols.policies, [{ name: 'app.read', source: 'config/tesseraql.yml', line: 72 }]);
  assert.deepEqual(symbols.messages, [{ name: 'users.list.title', source: 'messages/en.yml', line: 3 }]);
  assert.deepEqual(symbols.domains, [{ name: 'sku', source: 'domains/catalog.yml', line: 3 }]);
  assert.deepEqual(symbols.rules, [{ name: 'editableStatus', source: 'rules/inventory.yml', line: 7 }]);
  assert.deepEqual(symbols.routes,
      [{ id: 'app.home', source: 'web/get.yml', method: 'GET', path: '/', recipe: 'query-html' }]);
});

test('a pre-shared-definitions document degrades to empty domains and rules', () => {
  const symbols = parseAppSymbols(JSON.stringify({ policies: [], messages: [] }));
  assert.deepEqual(symbols.domains, []);
  assert.deepEqual(symbols.rules, []);
  assert.deepEqual(symbols.routes, []);
});

test('a route without a source is a contract error, missing identity parts are not', () => {
  assert.throws(() => parseAppSymbols(JSON.stringify(
      { policies: [], messages: [], routes: [{ id: 'x' }] })), SymbolsContractError);
  const symbols = parseAppSymbols(JSON.stringify(
      { policies: [], messages: [], routes: [{ source: 'batch/nightly.yml', id: null }] }));
  assert.deepEqual(symbols.routes,
      [{ id: null, source: 'batch/nightly.yml', method: null, path: null, recipe: null }]);
});

test('a route describes itself from whichever identity parts it has', () => {
  assert.equal(routeDescription(
      { id: 'users.list', source: 'web/api/users/get.yml', method: 'GET', path: '/api/users', recipe: 'query-json' }),
      'GET /api/users · query-json');
  assert.equal(routeDescription(
      { id: 'nightly', source: 'batch/nightly.yml', method: null, path: null, recipe: 'sql-batch' }),
      'sql-batch');
  assert.equal(routeDescription(
      { id: null, source: 'web/get.yml', method: null, path: null, recipe: null }),
      undefined);
});

test('rejects non-contract stdout', () => {
  assert.throws(() => parseAppSymbols('not json'), SymbolsContractError);
  assert.throws(() => parseAppSymbols('{"policies": []}'), SymbolsContractError);
  assert.throws(() => parseAppSymbols('{"policies": [{}], "messages": []}'), SymbolsContractError);
});

test('policy and message values resolve under the cursor', () => {
  const policy = symbolReferenceAt('  policy: app.read', 12);
  assert.deepEqual(policy, { kind: 'policy', value: 'app.read', start: 10, end: 18 });
  const message = symbolReferenceAt('    message: users.provision.unknown-user', 20);
  assert.equal(message?.kind, 'message');
  assert.equal(symbolReferenceAt('  policy: app.read', 3), undefined);
});

test('title/label values are maybe-message references', () => {
  assert.equal(symbolReferenceAt('title: view.items.new.title', 10)?.kind, 'maybe-message');
  assert.equal(symbolReferenceAt('    label: users.list.title', 15)?.kind, 'maybe-message');
});

test('domain and use values resolve under the cursor', () => {
  const domain = symbolReferenceAt('    domain: sku', 13);
  assert.deepEqual(domain, { kind: 'domain', value: 'sku', start: 12, end: 15 });
  const rule = symbolReferenceAt('    use: stockStaysNonNegative', 12);
  assert.equal(rule?.kind, 'rule');
  assert.equal(rule?.value, 'stockStaysNonNegative');
});

test('completion kind is detected mid-typing', () => {
  assert.equal(completionKindAt('  policy: app.', 14), 'policy');
  assert.equal(completionKindAt('  message: ', 11), 'message');
  assert.equal(completionKindAt('    domain: s', 13), 'domain');
  assert.equal(completionKindAt('    use: ', 9), 'rule');
  assert.equal(completionKindAt('  title: x', 10), undefined);
  assert.equal(completionKindAt('  policy: app.read extra', 24), undefined);
});
