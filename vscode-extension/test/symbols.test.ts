import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  completionKindAt,
  parseAppSymbols,
  symbolReferenceAt,
  SymbolsContractError,
} from '../src/core/symbols';

test('parses the symbols document', () => {
  const symbols = parseAppSymbols(JSON.stringify({
    policies: [{ name: 'app.read', source: 'config/tesseraql.yml', line: 72 }],
    messages: [{ key: 'users.list.title', source: 'messages/en.yml', line: 3 }],
    routes: [{ id: 'app.home', source: 'web/get.yml' }],
  }));
  assert.deepEqual(symbols.policies, [{ name: 'app.read', source: 'config/tesseraql.yml', line: 72 }]);
  assert.deepEqual(symbols.messages, [{ name: 'users.list.title', source: 'messages/en.yml', line: 3 }]);
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

test('completion kind is detected mid-typing', () => {
  assert.equal(completionKindAt('  policy: app.', 14), 'policy');
  assert.equal(completionKindAt('  message: ', 11), 'message');
  assert.equal(completionKindAt('  title: x', 10), undefined);
  assert.equal(completionKindAt('  policy: app.read extra', 24), undefined);
});
