import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  completionKindAt,
  parseAppSymbols,
  pathCompletionAt,
  pathReferenceAt,
  routeDescription,
  routesBinding,
  sourceCompletionAt,
  sourceDetail,
  sourceReferenceAt,
  stepLineOf,
  stepsDeclaredAbove,
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
  // A pre-0.18 CLI carries no sources, view bindings or embeds: they degrade to empty, not
  // to a contract error, so every other intelligence keeps working.
  assert.deepEqual(symbols.routes,
      [{ id: 'app.home', source: 'web/get.yml', method: 'GET', path: '/', recipe: 'query-html',
        sources: [], view: null, views: [], embeds: [] }]);
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
      [{ id: null, source: 'batch/nightly.yml', method: null, path: null, recipe: null,
        sources: [], view: null, views: [], embeds: [] }]);
});

test('a route describes itself from whichever identity parts it has', () => {
  assert.equal(routeDescription(
      { id: 'users.list', source: 'web/api/users/get.yml', method: 'GET', path: '/api/users', recipe: 'query-json',
        sources: [], view: null, views: [], embeds: [] }),
      'GET /api/users · query-json');
  assert.equal(routeDescription(
      { id: 'nightly', source: 'batch/nightly.yml', method: null, path: null, recipe: 'sql-batch',
        sources: [], view: null, views: [], embeds: [] }),
      'sql-batch');
  assert.equal(routeDescription(
      { id: null, source: 'web/get.yml', method: null, path: null, recipe: null,
        sources: [], view: null, views: [], embeds: [] }),
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

// --- Named sources (docs/editor-named-sources.md) ---

const DASHBOARD_ROUTE = {
  id: 'procurement.dashboard', source: 'web/dashboard/get.yml', method: 'GET',
  path: '/dashboard', recipe: 'query-html',
  sources: [
    { name: 'main', line: 10, arm: 'sql', file: 'totals.sql' },
    { name: 'ordersByState', line: 14, arm: 'sql', file: 'orders-by-state.sql' },
    { name: 'directory', line: 17, arm: 'service', file: null },
  ],
  view: 'procurement.dashboard.view', views: [], embeds: [],
};

test('parses each route\'s named sources and the views it binds', () => {
  const symbols = parseAppSymbols(JSON.stringify({
    policies: [], messages: [],
    routes: [
      DASHBOARD_ROUTE,
      { id: 'report', source: 'web/report/get.yml', method: 'GET', path: '/report',
        recipe: 'query-html', sources: [], view: null, views: ['procurement.dashboard.view'],
        embeds: ['procurement.recent.view'] },
      // A flow-form sources: block yields no line; a malformed entry is dropped, not fatal.
      { id: 'flow', source: 'web/flow/get.yml', method: 'GET', path: '/flow', recipe: 'query-json',
        sources: [{ name: 'main', line: null, arm: 'sql', file: 'x.sql' }, { line: 3 }],
        view: null, views: [] },
    ],
  }));
  assert.deepEqual(symbols.routes[0], DASHBOARD_ROUTE);
  assert.deepEqual(symbols.routes[1].views, ['procurement.dashboard.view']);
  assert.deepEqual(symbols.routes[1].embeds, ['procurement.recent.view']);
  assert.deepEqual(symbols.routes[2].sources,
      [{ name: 'main', line: null, arm: 'sql', file: 'x.sql' }]);
  // A CLI that binds views but carries no embeds yet: the array degrades to empty.
  assert.deepEqual(symbols.routes[2].embeds, []);
});

test('a view is bound by the routes that name it through view: or views:', () => {
  const symbols = parseAppSymbols(JSON.stringify({
    policies: [], messages: [],
    routes: [
      DASHBOARD_ROUTE,
      { id: 'report', source: 'web/report/get.yml', sources: [], view: null,
        views: ['procurement.dashboard.view'] },
      { id: 'other', source: 'web/other/get.yml', sources: [], view: 'other.view', views: [] },
    ],
  }));
  assert.deepEqual(routesBinding(symbols, 'procurement.dashboard.view').map((route) => route.id),
      ['procurement.dashboard', 'report']);
  assert.deepEqual(routesBinding(symbols, 'unbound.view'), []);
});

test('an embedded view is bound by every route hosting it, once per route', () => {
  // docs/view-composition.md wave 2b: an embedded view reads the HOST route's sources, so
  // its source: resolves through the hosts — which the contract names (docs/audit-low-leads.md
  // slice 21), because a view: scalar in a route is a binding, not an embedding.
  const symbols = parseAppSymbols(JSON.stringify({
    policies: [], messages: [],
    routes: [
      { id: 'host', source: 'web/host/get.yml', sources: [{ name: 'byStatus', line: 12 }],
        view: 'demo.host.view', views: [], embeds: ['demo.embedded.view'] },
      { id: 'report', source: 'web/report/get.yml', sources: [], view: null,
        views: ['demo.report.view'], embeds: ['demo.embedded.view'] },
      // Bound directly AND embedded through its own bound document: one location, not two.
      { id: 'both', source: 'web/both/get.yml', sources: [], view: 'demo.embedded.view',
        views: [], embeds: ['demo.embedded.view'] },
      { id: 'unrelated', source: 'web/other/get.yml', sources: [], view: 'other.view',
        views: [], embeds: [] },
    ],
  }));
  assert.deepEqual(routesBinding(symbols, 'demo.embedded.view').map((route) => route.id),
      ['host', 'report', 'both']);
  // The host's own document is bound by the host alone; embedding is not reflexive.
  assert.deepEqual(routesBinding(symbols, 'demo.host.view').map((route) => route.id), ['host']);
});

test('a source completion says its arm, its file and its route', () => {
  assert.equal(sourceDetail(DASHBOARD_ROUTE.sources[1], DASHBOARD_ROUTE),
      'sql · orders-by-state.sql · web/dashboard/get.yml');
  assert.equal(sourceDetail(DASHBOARD_ROUTE.sources[2], DASHBOARD_ROUTE),
      'service · web/dashboard/get.yml');
});

test('every source: in a view document is a reference — block form and flow map', () => {
  const view = 'dashboard.view.yml';
  const block = '    source: ordersByState';
  assert.deepEqual(sourceReferenceAt(view, block, 14, []),
      { value: 'ordersByState', start: 12, end: 25 });
  assert.equal(sourceReferenceAt(view, block, 5, []), undefined);
  const flow = '  - { type: stat, source: main, column: requisitions, title: Requisitions }';
  assert.deepEqual(sourceReferenceAt(view, flow, flow.indexOf('main') + 2, []),
      { value: 'main', start: flow.indexOf('main'), end: flow.indexOf('main') + 4 });
  assert.deepEqual(sourceReferenceAt(view, 'source: "受注一覧"', 10, []),
      { value: '受注一覧', start: 9, end: 13 });
  // A child's source: under children:, and the document's own top-level source:.
  assert.equal(sourceReferenceAt(view, '  - source: lines', 14,
      ['children:'])?.value, 'lines');
  assert.equal(sourceReferenceAt(view, 'source: main', 8, [])?.value, 'main');
  // resource: is not source:.
  assert.equal(sourceReferenceAt(view, '  resource: main', 14, []), undefined);
});

test('in a route document only an enrich: entry\'s bare-name source: is a reference', () => {
  const route = 'get.yml';
  const enriched = [
    'sources:',
    '  main:',
    '    sql:',
    '      file: orders.sql',
    '    enrich:',
    '      customer:',
    '        on: { customer_id: id }',
  ];
  const source = '        source: customers';
  assert.deepEqual(sourceReferenceAt(route, source, 18, enriched),
      { value: 'customers', start: 16, end: 25 });
  // A comment and a blank line between do not break the walk.
  assert.equal(sourceReferenceAt(route, source, 18, [...enriched, '', '        # joined'])?.value,
      'customers');
  // steps.<id> names a step, not a source.
  assert.equal(sourceReferenceAt(route, '        source: steps.lookup', 20, enriched), undefined);
  // The Studio validation builder's params: bind named source (shipped twice).
  const params = ['      params:'];
  assert.equal(sourceReferenceAt(route, '        source: params.source', 20, params), undefined);
  assert.equal(sourceReferenceAt(route, '        source: main', 20, params), undefined);
  // An enrichment's own sql: arm's params: — the chain is params, not the entry.
  assert.equal(sourceReferenceAt(route, '            source: main', 22,
      [...enriched, '        sql:', '          params:']), undefined);
  // on: under the entry maps columns.
  assert.equal(sourceReferenceAt(route, '          source: main', 20,
      [...enriched.slice(0, 6), '        on:']), undefined);
  // A lookup's source: is a URL (domains/*.yml, inputs) — and not a bare name anyway.
  assert.equal(sourceReferenceAt(route, '      source: /api/suppliers/search', 16,
      ['    lookup:']), undefined);
  // A top-level source: of a route document is nothing the route surface declares.
  assert.equal(sourceReferenceAt(route, 'source: main', 8, []), undefined);
});

test('source: completes in a view document and under an enrich: entry', () => {
  assert.equal(sourceCompletionAt('dashboard.view.yml', '    source: ord', 15, []), true);
  assert.equal(sourceCompletionAt('dashboard.view.yml', '  - { type: chart, source: ', 27, []),
      true);
  assert.equal(sourceCompletionAt('dashboard.view.yml', '    title: ord', 14, []), false);
  const enriched = ['    enrich:', '      customer:'];
  assert.equal(sourceCompletionAt('get.yml', '        source: cu', 18, enriched), true);
  assert.equal(sourceCompletionAt('get.yml', '        source: cu', 18, ['      params:']), false);
});

// --- Bindable paths (docs/editor-named-sources.md mechanism 3, docs/audit-low-leads.md slice 21) ---

test('a bindable path is detected by its value shape at every position that carries one', () => {
  // response.html.model, response.json.body, a notify payload, a step's params, a redirect
  // location placeholder — one shape, no key list.
  const model = '      users: main.rows';
  assert.deepEqual(pathReferenceAt(model, model.indexOf('main') + 1),
      { root: 'main', step: null, start: 13, end: 22 });
  // Anywhere on the path resolves — the segments belong to the root.
  assert.equal(pathReferenceAt(model, model.indexOf('rows') + 2)?.root, 'main');
  assert.equal(pathReferenceAt(model, 5), undefined);
  assert.deepEqual(pathReferenceAt('      count: ordersByState.rowCount', 20),
      { root: 'ordersByState', step: null, start: 13, end: 35 });
  const body = '      created: steps.main.affectedRows';
  assert.deepEqual(pathReferenceAt(body, 20),
      { root: 'steps', step: 'main', start: 15, end: 38 });
  const params = '        requisitionId: steps.header.keys.id';
  assert.equal(pathReferenceAt(params, 30)?.step, 'header');
  const location = '    location: /items/{steps.record.keys.id}';
  assert.deepEqual(pathReferenceAt(location, location.indexOf('record')),
      { root: 'steps', step: 'record', start: 22, end: 42 });
  // A flow-map payload, and a value trailed by a comment.
  const payload = '    payload: { order: steps.header.keys.id, total: main.first.total }';
  assert.equal(pathReferenceAt(payload, payload.indexOf('header'))?.step, 'header');
  assert.equal(pathReferenceAt(payload, payload.indexOf('first'))?.root, 'main');
  assert.equal(pathReferenceAt('        total: steps.headcount.body.total   # parsed', 22)?.step,
      'headcount');
  // The EN-02 positions: a job enrich source:, a chunk reader spool:, attach:, a push file:.
  assert.equal(pathReferenceAt('        source: steps.partner.rows', 26)?.step, 'partner');
  assert.equal(pathReferenceAt('      reader: { spool: steps.extract.spool }', 30)?.step,
      'extract');
  assert.equal(pathReferenceAt('      attach: steps.report.transferId', 20)?.step, 'report');
  assert.equal(pathReferenceAt('      file: steps.report.transferId', 20)?.step, 'report');
  // A quoted path, and a Unicode source name.
  assert.equal(pathReferenceAt('      data: "main.rows"', 16)?.root, 'main');
  assert.equal(pathReferenceAt('      注文: 受注一覧.rows', 10)?.root, '受注一覧');
});

test('a dotted value that is not a path is not a reference', () => {
  // A file, wherever it sits — the document link owns it.
  assert.equal(pathReferenceAt('      file: orders.sql', 16), undefined);
  assert.equal(pathReferenceAt('    template: report.html', 18), undefined);
  // Another detector's dotted reference: a message key, a policy, a view id, an id.
  assert.equal(pathReferenceAt('  message: procurement.rules.linesRequired', 20), undefined);
  assert.equal(pathReferenceAt('  policy: app.read', 12), undefined);
  assert.equal(pathReferenceAt('    view: procurement.dashboard.view', 18), undefined);
  assert.equal(pathReferenceAt('id: demo.dashboard', 6), undefined);
  assert.equal(pathReferenceAt('  - id: demo.step', 12), undefined);
  // An expression, a URL, a bare name, a comment.
  assert.equal(pathReferenceAt('      - when: main.rowCount == 0', 18), undefined);
  assert.equal(pathReferenceAt('      url: https://api.partner.example/v1', 20), undefined);
  assert.equal(pathReferenceAt('      data: main', 14), undefined);
  assert.equal(pathReferenceAt('      # totals: main.rows', 20), undefined);
  // A dotted KEY is not a value.
  assert.equal(pathReferenceAt('    app.read:', 6), undefined);
});

const COMMAND_ROUTE = [
  'version: tesseraql/v1',
  'id: demo.create',
  'kind: route',
  'recipe: command-json',
  'steps:',
  '  - id: header',
  '    sql:',
  '      file: create.sql',
  '      keys: [id]',
  '  - { id: lines, sql: { file: create-lines.sql } }',
  '  # a comment between items',
  '',
  '  - id: create',
  '    sql:',
  '      file: audit.sql',
  'response:',
  '  json:',
  '    body:',
  '      id: steps.header.keys.id',
];

test('steps.<id> resolves to the step item of the document\'s own steps: or pipeline: sequence', () => {
  assert.equal(stepLineOf(COMMAND_ROUTE, 'header'), 5);
  // A flow-form item, and an item after a comment and a blank line.
  assert.equal(stepLineOf(COMMAND_ROUTE, 'lines'), 9);
  assert.equal(stepLineOf(COMMAND_ROUTE, 'create'), 12);
  assert.equal(stepLineOf(COMMAND_ROUTE, 'ghost'), undefined);
  // The discriminator: the document's own top-level id: never resolves, even when a step
  // shares its last segment — `id: demo.create` is not `- id: create`.
  assert.equal(stepLineOf(COMMAND_ROUTE, 'demo.create'), undefined);
  // A job's pipeline: spells the same sequence.
  const job = ['id: nightly', 'kind: job', 'pipeline:', '  - id: extract', '    sql:',
    '      mode: query-spool', '  - id: load', '    chunk:',
    '      reader: { spool: steps.extract.spool }'];
  assert.equal(stepLineOf(job, 'extract'), 3);
  assert.equal(stepLineOf(job, 'load'), 6);
  // An id: item of another sequence (a view's fields:) is not a step.
  const view = ['kind: view', 'fields:', '  - id: sku', 'steps:', '  - id: main'];
  assert.equal(stepLineOf(view, 'sku'), undefined);
  assert.equal(stepLineOf(view, 'main'), 4);
});

test('the steps declared above a line are the ones a reference there may name', () => {
  assert.deepEqual(stepsDeclaredAbove(COMMAND_ROUTE, COMMAND_ROUTE.length),
      ['header', 'lines', 'create']);
  // In a pipeline, a step sees only the earlier ones.
  assert.deepEqual(stepsDeclaredAbove(COMMAND_ROUTE, 12), ['header', 'lines']);
  assert.deepEqual(stepsDeclaredAbove(COMMAND_ROUTE, 4), []);
});

test('a path completes: steps.<id> anywhere a scalar is typed, a root under the path blocks', () => {
  // After `steps.` the declared step ids, at any scalar position.
  assert.deepEqual(pathCompletionAt('      created: steps.', 21, []), { kind: 'step' });
  assert.deepEqual(pathCompletionAt('      file: steps.rep', 21, []), { kind: 'step' });
  assert.deepEqual(pathCompletionAt('      reader: { spool: steps.', 29, []), { kind: 'step' });
  // A root under model:/body:/payload:/params: — block form and the flow map on the key's line.
  assert.deepEqual(pathCompletionAt('      users: ma', 15, ['response:', '  html:', '    model:']),
      { kind: 'root' });
  assert.deepEqual(pathCompletionAt('      users: ', 13, ['    model:']), { kind: 'root' });
  assert.deepEqual(pathCompletionAt('    body: { data: ma', 20, []), { kind: 'root' });
  assert.deepEqual(pathCompletionAt('        requisitionId: st', 25, ['      params:']),
      { kind: 'root' });
  // Elsewhere a bare word is not asking for a root: a recipe, an id, a panel column, a title.
  assert.equal(pathCompletionAt('recipe: qu', 10, []), undefined);
  assert.equal(pathCompletionAt('  - id: ma', 10, ['steps:']), undefined);
  assert.equal(pathCompletionAt('      column: to', 16, ['panels:']), undefined);
  assert.equal(pathCompletionAt('      title: ma', 15, ['    model:']), undefined);
  // Past the root, only steps. completes — a source's segments are the SQL's columns.
  assert.equal(pathCompletionAt('      users: main.ro', 20, ['    model:']), undefined);
});
