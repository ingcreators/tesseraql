import * as vscode from 'vscode';
import { findAppHomes, homeOf } from './core/appHome';
import { terminalCommand } from './core/cli';
import { LintController } from './vscode/diagnostics';
import { AppExplorer } from './vscode/explorer';
import { ErrorCodeHoverProvider } from './vscode/hover';
import { ReferenceLinkProvider } from './vscode/referenceLinks';
import { SuiteTestController } from './vscode/testing';
import { ServeStatus } from './vscode/serveStatus';

// The thin editor shell over the existing engines (docs/vscode-extension.md): lint
// findings into the Problems panel, the CLI verbs as commands, the app explorer, and
// error-code hovers. All validation lives in the CLI this extension runs.

const TERMINAL_VERBS = ['serve', 'test', 'migrate', 'admission', 'package'] as const;

export function activate(context: vscode.ExtensionContext): void {
  const output = vscode.window.createOutputChannel('TesseraQL');
  const lint = new LintController(output);
  let homes = discoverHomes();
  const explorer = new AppExplorer(homes);
  const testing = new SuiteTestController(homes, output);
  const serveStatus = new ServeStatus();

  context.subscriptions.push(
      output,
      lint,
      testing,
      serveStatus,
      vscode.commands.registerCommand('tesseraql.openServer', () =>
          vscode.env.openExternal(vscode.Uri.parse(ServeStatus.serverUrl()))),
      vscode.window.registerTreeDataProvider('tesseraqlExplorer', explorer),
      vscode.languages.registerHoverProvider(
          ['yaml', 'sql', 'html', 'properties', 'plaintext'], new ErrorCodeHoverProvider()),
      vscode.languages.registerDocumentLinkProvider('yaml', new ReferenceLinkProvider()),
      vscode.commands.registerCommand('tesseraql.lint', () =>
          withHome(homes, (home) => void lint.lintNow(home))),
      vscode.commands.registerCommand('tesseraql.refreshExplorer', () => explorer.refresh()),
      vscode.workspace.onDidSaveTextDocument((document) => {
        const home = homeOf(document.uri.fsPath, homes);
        if (home !== undefined) {
          lint.scheduleLint(home);
        }
      }),
      vscode.workspace.onDidChangeWorkspaceFolders(() => {
        homes = discoverHomes();
        explorer.setHomes(homes);
        testing.setHomes(homes);
        if (homes.length > 0) {
          serveStatus.start();
        } else {
          serveStatus.stop();
        }
      }),
  );

  for (const verb of TERMINAL_VERBS) {
    context.subscriptions.push(vscode.commands.registerCommand(`tesseraql.${verb}`, () =>
        withHome(homes, (home) => runInTerminal(verb, home))));
  }

  const watcher = vscode.workspace.createFileSystemWatcher('**/*.{yml,sql,html}');
  const refresh = () => {
    explorer.refresh();
    testing.discover();
  };
  watcher.onDidCreate(refresh);
  watcher.onDidDelete(refresh);
  context.subscriptions.push(watcher);

  for (const home of homes) {
    lint.scheduleLint(home);
  }
  if (homes.length > 0) {
    serveStatus.start();
  }
}

export function deactivate(): void {
  // Disposal happens through context.subscriptions.
}

function discoverHomes(): string[] {
  const roots = (vscode.workspace.workspaceFolders ?? []).map((folder) => folder.uri.fsPath);
  const homes = findAppHomes(roots);
  void vscode.commands.executeCommand('setContext', 'tesseraql.hasApp', homes.length > 0);
  return homes;
}

/** Runs the action on the single app home, or on the quick-picked one. */
function withHome(homes: readonly string[], action: (home: string) => void): void {
  if (homes.length === 0) {
    void vscode.window.showInformationMessage(
        'TesseraQL: no app home (a folder holding config/tesseraql.yml) in this workspace.');
    return;
  }
  if (homes.length === 1) {
    action(homes[0]);
    return;
  }
  void vscode.window.showQuickPick(homes.slice(), { placeHolder: 'TesseraQL app home' })
      .then((home) => {
        if (home !== undefined) {
          action(home);
        }
      });
}

function runInTerminal(verb: string, home: string): void {
  const cliPath = vscode.workspace.getConfiguration('tesseraql').get<string>('cliPath', 'tesseraql');
  const terminal = vscode.window.createTerminal({ name: `TesseraQL ${verb}`, cwd: home });
  terminal.show();
  terminal.sendText(terminalCommand(cliPath, verb));
}
