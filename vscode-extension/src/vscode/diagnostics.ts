import { execFile } from 'node:child_process';
import * as path from 'node:path';
import * as vscode from 'vscode';
import { lintArgs } from '../core/cli';
import { ContractError, LintReport, parseLintReport } from '../core/findings';
import { errorCodeDocsUrl } from '../core/errorCodes';

/**
 * Runs the headless lint per app home (debounced on save) and publishes the findings
 * contract to the Problems panel at source:line:column. The extension never produces
 * findings itself — a missing or pre-contract CLI surfaces one actionable warning.
 */
export class LintController {
  private readonly collection: vscode.DiagnosticCollection;
  private readonly pending = new Map<string, NodeJS.Timeout>();
  private warnedAboutCli = false;

  constructor(private readonly output: vscode.OutputChannel) {
    this.collection = vscode.languages.createDiagnosticCollection('tesseraql');
  }

  dispose(): void {
    for (const timeout of this.pending.values()) {
      clearTimeout(timeout);
    }
    this.collection.dispose();
  }

  scheduleLint(appHome: string): void {
    const previous = this.pending.get(appHome);
    if (previous !== undefined) {
      clearTimeout(previous);
    }
    this.pending.set(appHome, setTimeout(() => {
      this.pending.delete(appHome);
      void this.lintNow(appHome);
    }, 400));
  }

  async lintNow(appHome: string): Promise<void> {
    const cliPath = vscode.workspace.getConfiguration('tesseraql').get<string>('cliPath', 'tesseraql');
    let report: LintReport;
    try {
      report = await this.runLint(cliPath, appHome);
    } catch (error) {
      this.reportCliProblem(cliPath, error);
      return;
    }
    this.publish(appHome, report);
    this.output.appendLine(
      `lint ${appHome}: ${report.errors} error(s), ${report.warnings} warning(s)`);
  }

  private runLint(cliPath: string, appHome: string): Promise<LintReport> {
    return new Promise((resolve, reject) => {
      execFile(cliPath, lintArgs(appHome), { cwd: appHome, maxBuffer: 16 * 1024 * 1024 },
          (error, stdout) => {
            // Exit code 1 with a findings document is the normal "lint failed" answer;
            // only a spawn failure or non-contract output is a problem.
            if (error !== undefined && error !== null && (error as NodeJS.ErrnoException).code === 'ENOENT') {
              reject(error);
              return;
            }
            try {
              resolve(parseLintReport(stdout));
            } catch (contractError) {
              reject(contractError);
            }
          });
    });
  }

  private publish(appHome: string, report: LintReport): void {
    const byFile = new Map<string, vscode.Diagnostic[]>();
    for (const finding of report.findings) {
      const file = path.join(appHome, ...finding.source.split('/'));
      const line = (finding.line ?? 1) - 1;
      const column = (finding.column ?? 1) - 1;
      const diagnostic = new vscode.Diagnostic(
          new vscode.Range(line, column, line, column + 1),
          finding.message,
          finding.severity === 'error'
              ? vscode.DiagnosticSeverity.Error
              : vscode.DiagnosticSeverity.Warning);
      diagnostic.source = 'tesseraql';
      const docsUrl = errorCodeDocsUrl(finding.code);
      diagnostic.code = docsUrl === undefined
          ? finding.code
          : { value: finding.code, target: vscode.Uri.parse(docsUrl) };
      const diagnostics = byFile.get(file) ?? [];
      diagnostics.push(diagnostic);
      byFile.set(file, diagnostics);
    }
    // Replace this app's findings wholesale so fixed findings clear.
    this.clear(appHome);
    for (const [file, diagnostics] of byFile) {
      this.collection.set(vscode.Uri.file(file), diagnostics);
    }
  }

  private clear(appHome: string): void {
    const prefix = appHome + path.sep;
    const stale: vscode.Uri[] = [];
    this.collection.forEach((uri) => {
      if (uri.fsPath.startsWith(prefix)) {
        stale.push(uri);
      }
    });
    for (const uri of stale) {
      this.collection.delete(uri);
    }
  }

  private reportCliProblem(cliPath: string, error: unknown): void {
    const reason = error instanceof ContractError
        ? `'${cliPath}' did not print the findings document — it may predate 'lint --format json'`
        : `'${cliPath}' could not be run`;
    this.output.appendLine(`lint skipped: ${reason}`);
    if (this.warnedAboutCli) {
      return;
    }
    this.warnedAboutCli = true;
    void vscode.window
        .showWarningMessage(`TesseraQL: ${reason}. Point tesseraql.cliPath at the project's CLI.`,
            'Open Settings')
        .then((choice) => {
          if (choice === 'Open Settings') {
            void vscode.commands.executeCommand('workbench.action.openSettings', 'tesseraql.cliPath');
          }
        });
  }
}
