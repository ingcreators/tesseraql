// The cross-surface findings contract (docs/vscode-extension.md): the JSON document
// `tesseraql lint --format json` prints and the MCP dev-tools' lint tool has always
// emitted. The extension parses it and renders it; it never produces findings itself.

export interface LintFinding {
  code: string;
  severity: string;
  source: string;
  message: string;
  line: number | null;
  column: number | null;
}

export interface LintReport {
  errors: number;
  warnings: number;
  findings: LintFinding[];
}

/** The stdout was not the findings document — a pre-contract CLI or a crash. */
export class ContractError extends Error {}

export function parseLintReport(stdout: string): LintReport {
  let parsed: unknown;
  try {
    parsed = JSON.parse(stdout);
  } catch {
    throw new ContractError('stdout is not JSON');
  }
  if (typeof parsed !== 'object' || parsed === null || !Array.isArray((parsed as any).findings)) {
    throw new ContractError('stdout is JSON but not the findings document');
  }
  const document = parsed as { errors?: unknown; warnings?: unknown; findings: unknown[] };
  const findings = document.findings.map(toFinding);
  return {
    errors: asCount(document.errors),
    warnings: asCount(document.warnings),
    findings,
  };
}

function toFinding(value: unknown): LintFinding {
  if (typeof value !== 'object' || value === null) {
    throw new ContractError('a finding is not an object');
  }
  const finding = value as Record<string, unknown>;
  return {
    code: asString(finding.code, 'code'),
    severity: asString(finding.severity, 'severity'),
    source: asString(finding.source, 'source'),
    message: asString(finding.message, 'message'),
    line: asPosition(finding.line),
    column: asPosition(finding.column),
  };
}

function asString(value: unknown, field: string): string {
  if (typeof value !== 'string') {
    throw new ContractError(`finding field '${field}' is not a string`);
  }
  return value;
}

function asPosition(value: unknown): number | null {
  return typeof value === 'number' && Number.isInteger(value) && value >= 1 ? value : null;
}

function asCount(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}
