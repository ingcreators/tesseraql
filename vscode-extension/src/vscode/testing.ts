import { execFile } from 'node:child_process';
import * as fs from 'node:fs';
import * as path from 'node:path';
import * as vscode from 'vscode';
import { suiteCases } from '../core/suites';
import { parseTestRunReport, TestRunReport } from '../core/testReport';

/**
 * Test Explorer over the app's declarative suites (docs/vscode-extension.md, Phase 55
 * slice 4): cases discovered from tests/**\/*.yml, runs executing
 * `tesseraql test --format json` per app home and mapped back by case name. A
 * coverage-kind run additionally feeds the per-file SQL line coverage from the same
 * document into the editor's test coverage API.
 */
export class SuiteTestController {
  private readonly controller: vscode.TestController;
  private homes: readonly string[];

  constructor(homes: readonly string[], private readonly output: vscode.OutputChannel) {
    this.homes = homes;
    this.controller = vscode.tests.createTestController('tesseraql', 'TesseraQL');
    this.controller.createRunProfile('Run', vscode.TestRunProfileKind.Run,
        (request, token) => void this.run(request, token, false), true);
    const coverage = this.controller.createRunProfile('Run with Coverage',
        vscode.TestRunProfileKind.Coverage, (request, token) => void this.run(request, token, true));
    coverage.loadDetailedCoverage = async (_run, fileCoverage) =>
        this.details.get(fileCoverage) ?? [];
    this.discover();
  }

  dispose(): void {
    this.controller.dispose();
  }

  setHomes(homes: readonly string[]): void {
    this.homes = homes;
    this.discover();
  }

  /** Rebuilds the suite tree: one item per suite file, one child per case. */
  discover(): void {
    this.controller.items.replace([]);
    for (const home of this.homes) {
      for (const suite of suiteFiles(path.join(home, 'tests'))) {
        const uri = vscode.Uri.file(suite);
        const item = this.controller.createTestItem(suite, path.basename(suite), uri);
        let text: string;
        try {
          text = fs.readFileSync(suite, 'utf8');
        } catch {
          continue;
        }
        for (const testCase of suiteCases(text)) {
          const child = this.controller.createTestItem(
              suite + '#' + testCase.name, testCase.name, uri);
          child.range = new vscode.Range(testCase.line, 0, testCase.line, 0);
          item.children.add(child);
        }
        this.controller.items.add(item);
      }
    }
  }

  private readonly details = new WeakMap<vscode.FileCoverage, vscode.StatementCoverage[]>();

  private async run(request: vscode.TestRunRequest, token: vscode.CancellationToken,
      withCoverage: boolean): Promise<void> {
    const run = this.controller.createTestRun(request);
    const byHome = this.casesByHome(request);
    for (const [home, cases] of byHome) {
      if (token.isCancellationRequested) {
        break;
      }
      for (const testCase of cases.values()) {
        run.enqueued(testCase);
      }
      let report: TestRunReport;
      try {
        // A request that names specific items runs only those cases (Phase 56).
        report = await this.execute(home,
            request.include === undefined ? undefined : [...cases.keys()]);
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        for (const testCase of cases.values()) {
          run.errored(testCase, new vscode.TestMessage(
              `tesseraql test did not produce the test-run document: ${message}`));
        }
        continue;
      }
      const results = new Map(report.results.map((result) => [result.name, result]));
      for (const [name, testCase] of cases) {
        const result = results.get(name);
        if (result === undefined) {
          run.skipped(testCase);
        } else if (result.passed) {
          run.passed(testCase);
        } else {
          run.failed(testCase, new vscode.TestMessage(result.message));
        }
      }
      if (withCoverage) {
        this.addCoverage(run, home, report);
      }
      this.output.appendLine(
          `test ${home}: ${report.passed} passed, ${report.failed} failed`);
    }
    run.end();
  }

  /** The requested cases (all discovered cases when the request has no include). */
  private casesByHome(request: vscode.TestRunRequest): Map<string, Map<string, vscode.TestItem>> {
    const byHome = new Map<string, Map<string, vscode.TestItem>>();
    const add = (item: vscode.TestItem) => {
      if (item.children.size > 0) {
        item.children.forEach(add);
        return;
      }
      const home = this.homes.find((candidate) =>
          item.uri !== undefined && item.uri.fsPath.startsWith(candidate + path.sep));
      if (home === undefined) {
        return;
      }
      const cases = byHome.get(home) ?? new Map<string, vscode.TestItem>();
      cases.set(item.label, item);
      byHome.set(home, cases);
    };
    if (request.include !== undefined) {
      request.include.forEach(add);
    } else {
      this.controller.items.forEach(add);
    }
    return byHome;
  }

  private execute(home: string, caseNames?: readonly string[]): Promise<TestRunReport> {
    const cliPath = vscode.workspace.getConfiguration('tesseraql').get<string>('cliPath', 'tesseraql');
    const args = ['test', '--app', home, '--format', 'json'];
    for (const name of caseNames ?? []) {
      args.push('--case', name);
    }
    return new Promise((resolve, reject) => {
      execFile(cliPath, args,
          { cwd: home, maxBuffer: 64 * 1024 * 1024 },
          (error, stdout) => {
            // Exit 1 with the document is a normal failing run; only a spawn failure
            // or non-contract output is an error.
            if (error !== undefined && error !== null
                && (error as NodeJS.ErrnoException).code === 'ENOENT') {
              reject(new Error(`'${cliPath}' could not be run`));
              return;
            }
            try {
              resolve(parseTestRunReport(stdout));
            } catch (contractError) {
              reject(contractError);
            }
          });
    });
  }

  private addCoverage(run: vscode.TestRun, home: string, report: TestRunReport): void {
    for (const file of report.sql) {
      const uri = vscode.Uri.file(path.join(home, ...file.file.split('/')));
      const covered = new Set(file.coveredLines);
      const statements = file.coverableLines.map((line) => new vscode.StatementCoverage(
          covered.has(line), new vscode.Position(line - 1, 0)));
      const coverage = new vscode.FileCoverage(uri,
          new vscode.TestCoverageCount(file.coveredLines.length, file.coverableLines.length));
      this.details.set(coverage, statements);
      run.addCoverage(coverage);
    }
  }
}

/** Every suite file under {@code testsDir}, sorted. */
function suiteFiles(testsDir: string): string[] {
  const found: string[] = [];
  const walk = (dir: string) => {
    let entries: fs.Dirent[];
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const entry of entries) {
      if (entry.name.startsWith('.')) {
        continue;
      }
      if (entry.isDirectory()) {
        walk(path.join(dir, entry.name));
      } else if (entry.name.endsWith('.yml')) {
        found.push(path.join(dir, entry.name));
      }
    }
  };
  walk(testsDir);
  return found.sort();
}
