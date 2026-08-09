import { execFile } from 'node:child_process';
import * as path from 'node:path';
import * as vscode from 'vscode';
import { homeOf } from '../core/appHome';
import {
  AppSymbols,
  BrokenDocument,
  completionKindAt,
  parseAppSymbols,
  symbolReferenceAt,
} from '../core/symbols';

/**
 * The language layer (docs/vscode-extension.md, Phase 56 slice 5): completion and
 * go-to-definition for `policy:`, `message:`, `domain:`, and `use:` values over the
 * `tesseraql symbols` contract — the editor knows exactly what the framework
 * declares, nothing more.
 */
function poolFor(symbols: AppSymbols,
    kind: 'policy' | 'message' | 'maybe-message' | 'domain' | 'shared' | 'decision'
        | 'workflow' | 'calendar' | 'job') {
  switch (kind) {
    case 'policy': return symbols.policies;
    case 'domain': return symbols.domains;
    // A use: names a shared rule in validate: and a shared decision in decide:; the
    // line alone cannot tell them apart, so both namespaces answer.
    case 'shared': return [...symbols.rules, ...symbols.decisions];
    case 'decision': return symbols.decisions;
    // The transition:/dispatch: suite targets name a workflow.
    case 'workflow': return symbols.workflows;
    // A schedule's business-day calendar (calendars/*.yml, docs/jobs.md).
    case 'calendar': return symbols.calendars;
    // trigger: after: chains to a declared job (docs/jobs.md).
    case 'job': return symbols.jobs;
    default: return symbols.messages;
  }
}
export class SymbolIndex {
  private readonly byHome = new Map<string, AppSymbols>();
  private readonly pending = new Map<string, NodeJS.Timeout>();
  private readonly refreshed: (() => void)[] = [];
  /** The last reported set of broken documents per home, so an unchanged set warns once. */
  private readonly warnedBroken = new Map<string, string>();

  constructor(private homes: readonly string[], private readonly output: vscode.OutputChannel) {
    for (const home of homes) {
      this.scheduleRefresh(home);
    }
  }

  /** Runs after a home's symbols land, so index-decorated views can re-render. */
  onDidRefresh(listener: () => void): void {
    this.refreshed.push(listener);
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
              const symbols = parseAppSymbols(stdout);
              this.byHome.set(home, symbols);
              this.reportBroken(home, symbols.broken);
              for (const listener of this.refreshed) {
                listener();
              }
            } catch {
              // A pre-contract CLI or a broken app: keep the last good index; the
              // lint loop owns the actionable message.
              this.output.appendLine(`symbols skipped for ${home}`);
            }
            resolve();
          });
    });
  }

  /**
   * Names the documents the CLI skipped. Their symbols are simply missing, so without this the
   * only evidence is a completion list that quietly stops offering one file's names — the kind
   * of absence a user reads as "the extension is broken" rather than "that document is".
   * Logged every refresh; the popup appears once per set of files, so a document that stays
   * broken while it is being edited does not nag.
   */
  private reportBroken(home: string, broken: readonly BrokenDocument[]): void {
    const signature = broken.map((document) => document.source).sort().join('\n');
    if (signature === this.warnedBroken.get(home)) {
      return;
    }
    this.warnedBroken.set(home, signature);
    for (const document of broken) {
      this.output.appendLine(`symbols: skipped ${document.source}: ${document.error}`);
    }
    if (broken.length === 0) {
      return;
    }
    const files = broken.map((document) => document.source).join(', ');
    void vscode.window
        .showWarningMessage(
            `TesseraQL: ${broken.length} document(s) did not parse, so their symbols are missing`
                + ` from completion and go-to-definition: ${files}`,
            'Show Log')
        .then((choice) => {
          if (choice === 'Show Log') {
            this.output.show(true);
          }
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
    const pool = poolFor(found.symbols, reference.kind);
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
    const pool = poolFor(found.symbols, kind);
    return pool.map((symbol) => {
      const item = new vscode.CompletionItem(symbol.name,
          kind === 'message'
              ? vscode.CompletionItemKind.Text
              : vscode.CompletionItemKind.Value);
      // A job completion says how the target starts — "after x" chains read at a glance.
      const trigger = (symbol as { trigger?: string | null }).trigger;
      item.detail = typeof trigger === 'string' && trigger !== ''
          ? `${trigger} · ${symbol.source}`
          : symbol.source;
      return item;
    });
  }
}
