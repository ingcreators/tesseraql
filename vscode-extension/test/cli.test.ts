import assert from 'node:assert/strict';
import { test } from 'node:test';
import { lintArgs, terminalCommand } from '../src/core/cli';
import { errorCodeDocsUrl } from '../src/core/errorCodes';

test('lint runs the JSON contract against the app home', () => {
  assert.deepEqual(lintArgs('/work/shop'), ['lint', '--app', '/work/shop', '--format', 'json']);
});

test('terminal verbs run against the current directory', () => {
  assert.equal(terminalCommand('tesseraql', 'serve'), 'tesseraql serve --app .');
  assert.equal(terminalCommand('/opt/t q l/bin/tesseraql', 'test'),
      '"/opt/t q l/bin/tesseraql" test --app .');
});

test('error codes link to their domain section in the published reference', () => {
  assert.equal(errorCodeDocsUrl('TQL-YAML-1035'),
      'https://ingcreators.com/tesseraql/reference-error-codes/#yaml');
  assert.equal(errorCodeDocsUrl('not-a-code'), undefined);
});
