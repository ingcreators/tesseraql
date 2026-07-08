import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseTestRunReport, TestContractError } from '../src/core/testReport';

test('parses the test-run document', () => {
  const report = parseTestRunReport(JSON.stringify({
    passed: 1,
    failed: 1,
    results: [
      { name: 'the smoke case', passed: true, message: 'OK' },
      { name: 'the failing case', passed: false, message: 'rowCount expected 1 but was 0' },
    ],
    sql: [{
      file: 'web/api/items/search.sql',
      lineRatio: 0.5,
      branchRatio: 1.0,
      coveredLines: [3],
      coverableLines: [3, 7],
    }],
  }));
  assert.equal(report.passed, 1);
  assert.equal(report.failed, 1);
  assert.equal(report.results[1].passed, false);
  assert.deepEqual(report.sql[0].coverableLines, [3, 7]);
});

test('rejects non-JSON and non-contract stdout', () => {
  assert.throws(() => parseTestRunReport('TesseraQL tests: 2 passed, 0 failed'), TestContractError);
  assert.throws(() => parseTestRunReport('{"results": []}'), TestContractError);
  assert.throws(() => parseTestRunReport('{"results": [1], "sql": []}'), TestContractError);
});

test('normalizes malformed lines and counts', () => {
  const report = parseTestRunReport(JSON.stringify({
    results: [{ name: 'a', passed: true }],
    sql: [{ file: 'x.sql', coveredLines: [0, 2, 'x'], coverableLines: null }],
  }));
  assert.equal(report.failed, 0);
  assert.equal(report.results[0].message, '');
  assert.deepEqual(report.sql[0].coveredLines, [2]);
  assert.deepEqual(report.sql[0].coverableLines, []);
});
