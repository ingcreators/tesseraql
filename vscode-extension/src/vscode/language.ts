import { execFile } from 'node:child_process';
import * as path from 'node:path';
import * as vscode from 'vscode';
import { homeOf } from '../core/appHome';
import {
  AppSymbols,
  completionKindAt,
  parseAppSymbols,
  symbolReferenceAt,
} from '../core/symbols';

/**
 * The language layer (docs/vscode-extension.md, Phase 56 slice 5): completion and
 * go-to-definition for `policy:` and `message:` values over the `tesseraql symbols`
 * contract — the editor knows exactly what the framework declares, nothing more.
 */
export class SymbolIndex {
  private readonly byHome = new Map<string, AppSymbols>();
  private readonly pending = new Map<string, NodeJS.Timeout>();

  constructor(private homes: readonly string[], private readonly output: vscode.OutputChannel) {
    for (const home of homes) {
      this.scheduleRefresh(home);
    }
  }

  dispose(): void {
    for (const timeout of this.pending.values()) {
      clearTimeout(timeout);
    }
  }

  setHomes(homes: readonly string[]): void {
    this.homes = homes;
    for (const home of homes) {
      this.scheduleRefresh(home);
    }
  }

  scheduleRefresh(home: string): void {
    const previous = this.pending.get(home);
    if (previous !== undefined) {
      clearTimeout(previous);
    }
    this.pending.set(home, setTimeout(() => {
      this.pending.delete(home);
      void this.refresh(home);
    }, 400));
  }

  symbolsFor(file: string): { home: string; symbols: AppSymbols } | undefined {
    const home = homeOf(file, this.homes);
    if (home === undefined) {
      return undefined;
    }
    const symbols = this.byHome.get(home);
    return symbols === undefined ? undefined : { home, symbols };
  }

  private refresh(home: string): Promise<void> {
    const cliPath = vscode.workspace.getConfiguration('tesseraql').get<string>('cliPath', 'tesseraql');
    return new Promise((resolve) => {
      execFile(cliPath, ['symbols', '--app', home], { cwd: home, maxBuffer: 16 * 1024 * 1024 },
          (_error, stdout) => {
            try {
              this.byHome.set(home, parseAppSymbols(stdout));
            } catch {
              // A pre-contract CLI or a broken app: keep the last good index; the
              // lint loop owns the actionable message.
              this.output.appendLine(`symbols skipped for ${home}`);
            }
            resolve();
          });
    });
  }
}

export class SymbolDefinitionProvider implements vscode.DefinitionProvider {
  constructor(private readonly index: SymbolIndex) {}

  provideDefinition(document: vscode.TextDocument, position: vscode.Position):
      vscode.Location | undefined {
    const found = this.index.symbolsFor(document.uri.fsPath);
    if (found === undefined) {
      return undefined;
    }
    const reference = symbolReferenceAt(document.lineAt(position.line).text, position.character);
    if (reference === undefined) {
      return undefined;
    }
    const pool = reference.kind === 'policy' ? found.symbols.policies : found.symbols.messages;
    const target = pool.find((symbol) => symbol.name === reference.value);
    if (target === undefined) {
      // A maybe-message (title:/label:) that names no key is a literal, not an error.
      return undefined;
    }
    return new vscode.Location(
        vscode.Uri.file(path.join(found.home, ...target.source.split('/'))),
        new vscode.Position((target.line ?? 1) - 1, 0));
  }
}

export class SymbolCompletionProvider implements vscode.CompletionItemProvider {
  constructor(private readonly index: SymbolIndex) {}

  provideCompletionItems(document: vscode.TextDocument, position: vscode.Position):
      vscode.CompletionItem[] | undefined {
    const found = this.index.symbolsFor(document.uri.fsPath);
    if (found === undefined) {
      return undefined;
    }
    const kind = completionKindAt(document.lineAt(position.line).text, position.character);
    if (kind === undefined) {
      return undefined;
    }
    const pool = kind === 'policy' ? found.symbols.policies : found.symbols.messages;
    return pool.map((symbol) => {
      const item = new vscode.CompletionItem(symbol.name,
          kind === 'policy'
              ? vscode.CompletionItemKind.Value
              : vscode.CompletionItemKind.Text);
      item.detail = symbol.source;
      return item;
    });
  }
}
