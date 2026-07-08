import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ContractError, parseLintReport } from '../src/core/findings';

test('parses the cross-surface findings document', () => {
  const report = parseLintReport(JSON.stringify({
    errors: 1,
    warnings: 1,
    findings: [
      {
        code: 'TQL-SQL-2103',
        severity: 'error',
        source: 'web/broken/get.yml',
        message: 'Referenced SQL file is missing: missing.sql',
        line: 12,
        column: 3,
      },
      {
        code: 'TQL-SEC-4030',
        severity: 'warning',
        source: 'mcp/ui.yml',
        message: 'undefined policy',
        line: null,
        column: null,
        error: false,
      },
    ],
  }));
  assert.equal(report.errors, 1);
  assert.equal(report.warnings, 1);
  assert.equal(report.findings.length, 2);
  assert.deepEqual(report.findings[0], {
    code: 'TQL-SQL-2103',
    severity: 'error',
    source: 'web/broken/get.yml',
    message: 'Referenced SQL file is missing: missing.sql',
    line: 12,
    column: 3,
  });
  assert.equal(report.findings[1].line, null);
  assert.equal(report.findings[1].column, null);
});

test('a clean report has no findings', () => {
  const report = parseLintReport('{"errors": 0, "warnings": 0, "findings": []}');
  assert.equal(report.errors, 0);
  assert.equal(report.findings.length, 0);
});

test('rejects non-JSON stdout (a pre-contract CLI)', () => {
  assert.throws(() => parseLintReport('TesseraQL lint: 0 finding(s), 0 error(s)'), ContractError);
});

test('rejects JSON that is not the findings document', () => {
  assert.throws(() => parseLintReport('{"other": true}'), ContractError);
  assert.throws(() => parseLintReport('{"findings": [42]}'), ContractError);
  assert.throws(() => parseLintReport('{"findings": [{"code": 1}]}'), ContractError);
});

test('normalizes out-of-contract positions to null', () => {
  const report = parseLintReport(JSON.stringify({
    findings: [{ code: 'TQL-X-1', severity: 'error', source: 'a.yml', message: 'm', line: 0, column: 1.5 }],
  }));
  assert.equal(report.findings[0].line, null);
  assert.equal(report.findings[0].column, null);
});
