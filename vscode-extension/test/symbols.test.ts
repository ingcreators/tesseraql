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
    decisions: [{ name: 'approvalRoute', source: 'decisions/approval.yml', line: 4 }],
    routes: [{ id: 'app.home', source: 'web/get.yml', method: 'GET', path: '/', recipe: 'query-html' }],
  }));
  assert.deepEqual(symbols.policies, [{ name: 'app.read', source: 'config/tesseraql.yml', line: 72 }]);
  assert.deepEqual(symbols.messages, [{ name: 'users.list.title', source: 'messages/en.yml', line: 3 }]);
  assert.deepEqual(symbols.domains, [{ name: 'sku', source: 'domains/catalog.yml', line: 3 }]);
  assert.deepEqual(symbols.rules, [{ name: 'editableStatus', source: 'rules/inventory.yml', line: 7 }]);
  assert.deepEqual(symbols.decisions,
      [{ name: 'approvalRoute', source: 'decisions/approval.yml', line: 4 }]);
  assert.deepEqual(symbols.routes,
      [{ id: 'app.home', source: 'web/get.yml', method: 'GET', path: '/', recipe: 'query-html' }]);
});

test('skipped documents are read from the broken array', () => {
  const symbols = parseAppSymbols(JSON.stringify({
    policies: [],
    messages: [],
    broken: [{ source: 'web/users/get.yml', error: 'mapping values are not allowed here' }],
  }));
  assert.deepEqual(symbols.broken,
      [{ source: 'web/users/get.yml', error: 'mapping values are not allowed here' }]);
});

test('a CLI that loaded strictly reports no broken documents', () => {
  // It would have failed the whole run instead, so the array is simply absent.
  assert.deepEqual(parseAppSymbols(JSON.stringify({ policies: [], messages: [] })).broken, []);
});

test('a pre-shared-definitions document degrades to empty domains, rules, and decisions', () => {
  const symbols = parseAppSymbols(JSON.stringify({ policies: [], messages: [] }));
  assert.deepEqual(symbols.domains, []);
  assert.deepEqual(symbols.rules, []);
  assert.deepEqual(symbols.decisions, []);
  assert.deepEqual(symbols.calendars, []);
  assert.deepEqual(symbols.catalogs, []);
  assert.deepEqual(symbols.routes, []);
  assert.deepEqual(symbols.workflows, []);
  assert.deepEqual(symbols.jobs, []);
});

test('codes: navigates and completes against the declared catalogs', () => {
  const reference = symbolReferenceAt('    codes: 取引区分', 12);
  assert.equal(reference?.kind, 'catalog');
  assert.equal(reference?.value, '取引区分');
  assert.equal(completionKindAt('    codes: ', 11), 'catalog');
  // A flow-map domain completes the same way its policy: and domain: siblings do.
  assert.equal(completionKindAt('  取引区分: { type: string, codes: ', 34), 'catalog');
});

test('parses the code catalogs a domain may reference', () => {
  const symbols = parseAppSymbols(JSON.stringify({
    policies: [], messages: [],
    catalogs: [{ name: '取引区分', source: 'catalogs/codes.yml', line: 3 }],
  }));
  assert.deepEqual(symbols.catalogs,
      [{ name: '取引区分', source: 'catalogs/codes.yml', line: 3 }]);
});

test('parses calendars and jobs with their trigger stories', () => {
  const symbols = parseAppSymbols(JSON.stringify({
    policies: [], messages: [],
    calendars: [{ name: 'jp-banking', source: 'calendars/jp.yml', line: 4 }],
    jobs: [{
      id: 'nightly.close', source: 'batch/close/job.yml', line: 2,
      trigger: 'cron 0 0 2 * * ?, calendar jp-banking (day 5)',
    }],
  }));
  assert.deepEqual(symbols.calendars,
      [{ name: 'jp-banking', source: 'calendars/jp.yml', line: 4 }]);
  assert.deepEqual(symbols.jobs, [{
    name: 'nightly.close', source: 'batch/close/job.yml', line: 2,
    trigger: 'cron 0 0 2 * * ?, calendar jp-banking (day 5)',
  }]);
});

test('calendar: and after: values resolve under the cursor and complete', () => {
  const calendar = symbolReferenceAt('    calendar: jp-banking', 16);
  assert.deepEqual(calendar, { kind: 'calendar', value: 'jp-banking', start: 14, end: 24 });
  const after = symbolReferenceAt('  after: extract.orders', 12);
  assert.equal(after?.kind, 'job');
  assert.equal(after?.value, 'extract.orders');
  assert.equal(completionKindAt('    calendar: jp', 16), 'calendar');
  assert.equal(completionKindAt('  after: ', 9), 'job');
});

test('parses workflows with their transition and dispatch ids', () => {
  const symbols = parseAppSymbols(JSON.stringify({
    policies: [], messages: [],
    workflows: [{
      id: 'purchase_request', source: 'workflow/purchase_request.yml', line: 2,
      transitions: ['approve', 'escalate'], dispatches: ['decide_next'],
    }],
  }));
  assert.deepEqual(symbols.workflows, [{
    name: 'purchase_request', source: 'workflow/purchase_request.yml', line: 2,
    transitions: ['approve', 'escalate'], dispatches: ['decide_next'],
  }]);
});

test('a workflow: value is a workflow reference and completes as one', () => {
  const line = "      workflow: purchase_request";
  const reference = symbolReferenceAt(line, line.indexOf('purchase') + 3);
  assert.deepEqual(reference, {
    kind: 'workflow', value: 'purchase_request',
    start: line.indexOf('purchase_request'), end: line.length,
  });
  assert.equal(completionKindAt('      workflow: pur', 19), 'workflow');
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

test('domain, use, and decision values resolve under the cursor', () => {
  const domain = symbolReferenceAt('    domain: sku', 13);
  assert.deepEqual(domain, { kind: 'domain', value: 'sku', start: 12, end: 15 });
  // A use: names a rule in validate: and a decision in decide: — the shared kind.
  const shared = symbolReferenceAt('    use: stockStaysNonNegative', 12);
  assert.equal(shared?.kind, 'shared');
  assert.equal(shared?.value, 'stockStaysNonNegative');
  const decision = symbolReferenceAt('    decision: approvalRoute', 20);
  assert.equal(decision?.kind, 'decision');
  assert.equal(decision?.value, 'approvalRoute');
});

test('flow-map domain: and policy: values complete — the wave-4 input shape', () => {
  // salary: { domain: salary, policy: hr.write } (docs/view-composition.md wave 4).
  const line = '  salary: { domain: salary, policy: hr.write }';
  assert.equal(completionKindAt(line, line.indexOf('salary,')), 'domain');
  assert.equal(completionKindAt(line, line.indexOf(' }')), 'policy');
  const open = '  salary: { domain: sal';
  assert.equal(completionKindAt(open, open.length), 'domain');
});

test('completion kind is detected mid-typing', () => {
  assert.equal(completionKindAt('  policy: app.', 14), 'policy');
  assert.equal(completionKindAt('  message: ', 11), 'message');
  assert.equal(completionKindAt('    domain: s', 13), 'domain');
  assert.equal(completionKindAt('    use: ', 9), 'shared');
  assert.equal(completionKindAt('    decision: appr', 18), 'decision');
  assert.equal(completionKindAt('  title: x', 10), undefined);
  assert.equal(completionKindAt('  policy: app.read extra', 24), undefined);
});
