import assert from 'node:assert/strict';
import { test } from 'node:test';
import { referenceLinks } from '../src/core/links';

test('sql file, view, and template references link with exact spans', () => {
  const text = [
    'sql:',
    '  file: search.sql',
    'response:',
    '  html:',
    '    view: list.view.yml',
    '    template: index.html',
  ].join('\n');
  assert.deepEqual(referenceLinks(text), [
    { line: 1, start: 8, end: 18, target: 'search.sql' },
    { line: 4, start: 10, end: 23, target: 'list.view.yml' },
    { line: 5, start: 14, end: 24, target: 'index.html' },
  ]);
});

test('a fragment suffix links to the file part only', () => {
  const links = referenceLinks('slots:\n  header: x\n  template: frags.html::new-link\n');
  assert.deepEqual(links, [{ line: 2, start: 12, end: 22, target: 'frags.html' }]);
});

test('a view document kind is not a reference', () => {
  assert.deepEqual(referenceLinks('kind: view\nview: list\n'), []);
});

test('step files and list items link, comments do not', () => {
  const text = [
    'steps:',
    '  record:',
    '    file: insert.sql',
    '# file: not-a-ref.sql',
    '  - file: also.sql',
  ].join('\n');
  assert.deepEqual(referenceLinks(text).map((link) => link.target),
      ['insert.sql', 'also.sql']);
});

test('quoted values link without the quotes', () => {
  const links = referenceLinks('sql:\n  file: "search.sql"\n');
  assert.deepEqual(links, [{ line: 1, start: 9, end: 19, target: 'search.sql' }]);
});
