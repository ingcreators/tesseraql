// Test-suite discovery (docs/vscode-extension.md, Phase 55 slice 4): case names and
// their lines from a tests/**/*.yml suite — presentation, not semantics. Cases are
// the `- name:` entries at the list indentation directly under `tests:`; deeper
// `- name:` lines (e.g. expected result rows) are data, not cases.

export interface SuiteCase {
  name: string;
  /** 0-based line of the case's `- name:` entry. */
  line: number;
}

const TESTS_ROOT = /^tests:\s*(#.*)?$/;
const CASE_ENTRY = /^(\s*)-\s+name:\s*(["']?)(.*?)\2\s*(#.*)?$/;

export function suiteCases(text: string): SuiteCase[] {
  const cases: SuiteCase[] = [];
  const lines = text.split('\n');
  let inTests = false;
  let caseIndent: number | undefined;
  for (let line = 0; line < lines.length; line++) {
    if (TESTS_ROOT.test(lines[line])) {
      inTests = true;
      caseIndent = undefined;
      continue;
    }
    if (!inTests) {
      continue;
    }
    const match = CASE_ENTRY.exec(lines[line]);
    if (match === null) {
      continue;
    }
    caseIndent = caseIndent ?? match[1].length;
    if (match[1].length === caseIndent && match[3] !== '') {
      cases.push({ name: match[3], line });
    }
  }
  return cases;
}
