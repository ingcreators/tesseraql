import assert from 'node:assert/strict';
import { test } from 'node:test';
import { suiteCases } from '../src/core/suites';

test('discovers case names and lines under tests:', () => {
  const text = [
    '# Starter smoke suite',
    'tests:',
    '  - name: the items search returns the seeded row',
    '    sql:',
    '      file: web/api/items/search.sql',
    '  - name: the items search filters by exact name',
    '    params:',
    '      q: First item',
  ].join('\n');
  assert.deepEqual(suiteCases(text), [
    { name: 'the items search returns the seeded row', line: 2 },
    { name: 'the items search filters by exact name', line: 5 },
  ]);
});

test('expected result rows are data, not cases', () => {
  const text = [
    'tests:',
    '  - name: the search returns the seeded row',
    '    expect:',
    '      rowCount: 1',
    '      rows:',
    '        - name: First item',
  ].join('\n');
  assert.deepEqual(suiteCases(text).map((suiteCase) => suiteCase.name),
      ['the search returns the seeded row']);
});

test('quoted names unquote; nothing before tests: counts', () => {
  const text = [
    'setup:',
    '  - name: not a case',
    'tests:',
    '  - name: "quoted case"',
  ].join('\n');
  assert.deepEqual(suiteCases(text), [{ name: 'quoted case', line: 3 }]);
});
