// The editor test-run contract (docs/vscode-extension.md, Phase 55 slice 3): the JSON
// document `tesseraql test --format json` prints — complete per-case results plus
// per-file SQL coverage with 1-based covered/coverable line lists.

export interface CaseResult {
  name: string;
  passed: boolean;
  message: string;
}

export interface SqlFileCoverage {
  file: string;
  lineRatio: number;
  branchRatio: number;
  coveredLines: number[];
  coverableLines: number[];
}

export interface TestRunReport {
  passed: number;
  failed: number;
  results: CaseResult[];
  sql: SqlFileCoverage[];
}

/** The stdout was not the test-run document — a pre-contract CLI or a crash. */
export class TestContractError extends Error {}

export function parseTestRunReport(stdout: string): TestRunReport {
  let parsed: unknown;
  try {
    parsed = JSON.parse(stdout);
  } catch {
    throw new TestContractError('stdout is not JSON');
  }
  if (typeof parsed !== 'object' || parsed === null
      || !Array.isArray((parsed as any).results) || !Array.isArray((parsed as any).sql)) {
    throw new TestContractError('stdout is JSON but not the test-run document');
  }
  const document = parsed as { passed?: unknown; failed?: unknown; results: unknown[]; sql: unknown[] };
  return {
    passed: asCount(document.passed),
    failed: asCount(document.failed),
    results: document.results.map(toResult),
    sql: document.sql.map(toCoverage),
  };
}

function toResult(value: unknown): CaseResult {
  const result = asObject(value, 'result');
  return {
    name: asString(result.name, 'name'),
    passed: result.passed === true,
    message: typeof result.message === 'string' ? result.message : '',
  };
}

function toCoverage(value: unknown): SqlFileCoverage {
  const coverage = asObject(value, 'sql entry');
  return {
    file: asString(coverage.file, 'file'),
    lineRatio: asCount(coverage.lineRatio),
    branchRatio: asCount(coverage.branchRatio),
    coveredLines: asLines(coverage.coveredLines),
    coverableLines: asLines(coverage.coverableLines),
  };
}

function asObject(value: unknown, what: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null) {
    throw new TestContractError(`a ${what} is not an object`);
  }
  return value as Record<string, unknown>;
}

function asString(value: unknown, field: string): string {
  if (typeof value !== 'string') {
    throw new TestContractError(`field '${field}' is not a string`);
  }
  return value;
}

function asCount(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

function asLines(value: unknown): number[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.filter((line): line is number => Number.isInteger(line) && line >= 1);
}
