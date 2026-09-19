import { execFile } from 'node:child_process';
import * as path from 'node:path';
import * as vscode from 'vscode';
import { homeOf } from '../core/appHome';
import {
  AppSymbols,
  BrokenDocument,
  RouteSymbol,
  completionKindAt,
  parseAppSymbols,
  pathCompletionAt,
  pathReferenceAt,
  routesBinding,
  sourceCompletionAt,
  sourceDetail,
  sourceReferenceAt,
  stepLineOf,
  stepsDeclaredAbove,
  symbolReferenceAt,
} from '../core/symbols';
import { viewIdInfoOf } from '../core/views';

/**
 * The language layer (docs/vscode-extension.md, Phase 56 slice 5): completion and
 * go-to-definition for `policy:`, `message:`, `domain:`, and `use:` values over the
 * `tesseraql symbols` contract — the editor knows exactly what the framework
 * declares, nothing more. A named source (docs/editor-named-sources.md) is the one
 * kind declared in a route and referenced from a document that does not name it: a
 * view's `source:` resolves through the routes that bind the view. A bindable path
 * (`users: main.rows`, `created: steps.main.affectedRows`) resolves by its root: a
 * source of the document's own route, or a step the document declares.
 */
function poolFor(symbols: AppSymbols,
    kind: 'policy' | 'message' | 'maybe-message' | 'domain' | 'shared' | 'decision'
        | 'workflow' | 'calendar' | 'job' | 'catalog') {
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
    // A domain's codes: names a code catalog (docs/code-catalogs.md).
    case 'catalog': return symbols.catalogs;
    default: return symbols.messages;
  }
}
/** The lines before the cursor's — the block contexts read upward. */
function linesAbove(document: vscode.TextDocument, line: number): string[] {
  const lines: string[] = [];
  for (let index = 0; index < line; index++) {
    lines.push(document.lineAt(index).text);
  }
  return lines;
}

/**
 * The routes whose `sources:` a `source:` in this document refers to: for a view
 * document, the routes binding it (through `view:` or `views:`); for a route document,
 * the route itself.
 */
function sourceScope(home: string, symbols: AppSymbols, document: vscode.TextDocument):
    RouteSymbol[] {
  const file = document.uri.fsPath;
  if (file.endsWith('.view.yml')) {
    return routesBinding(symbols, viewIdInfoOf(path.basename(file), document.getText()).id);
  }
  const relative = path.relative(home, file).split(path.sep).join('/');
  return symbols.routes.filter((route) => route.source === relative);
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
      vscode.Location | vscode.Location[] | undefined {
    const found = this.index.symbolsFor(document.uri.fsPath);
    if (found === undefined) {
      return undefined;
    }
    const lineText = document.lineAt(position.line).text;
    const reference = symbolReferenceAt(lineText, position.character);
    if (reference === undefined) {
      return this.sourceDefinition(found.home, found.symbols, document, position, lineText);
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

  /**
   * A `source:` value → the `sources.<name>:` line of every route that binds the
   * document (docs/editor-named-sources.md decision 3): one location per binding route,
   * so a view two routes share shows both. A name no binding route declares resolves
   * nothing — TQL-VIEW-3308 is the judgement, not the editor.
   */
  private sourceDefinition(home: string, symbols: AppSymbols, document: vscode.TextDocument,
      position: vscode.Position, lineText: string): vscode.Location[] | undefined {
    const reference = sourceReferenceAt(document.uri.fsPath, lineText, position.character,
        linesAbove(document, position.line));
    if (reference === undefined) {
      return this.pathDefinition(home, symbols, document, position, lineText);
    }
    const locations: vscode.Location[] = [];
    for (const route of sourceScope(home, symbols, document)) {
      const source = route.sources.find((candidate) => candidate.name === reference.value);
      if (source !== undefined) {
        locations.push(new vscode.Location(
            vscode.Uri.file(path.join(home, ...route.source.split('/'))),
            new vscode.Position((source.line ?? 1) - 1, 0)));
      }
    }
    return locations.length === 0 ? undefined : locations;
  }

  /**
   * A bindable path → what its root names: `steps.<id>…` → the `- id: <id>` line of the
   * document's own `steps:`/`pipeline:` sequence (docs/audit-low-leads.md slice 21); any
   * other root → the `sources.<root>:` line of the document's route. A root that is neither
   * resolves nothing — `params`, `path`, `batch` and the other ambient roots are the
   * framework's, declared nowhere in the app.
   */
  private pathDefinition(home: string, symbols: AppSymbols, document: vscode.TextDocument,
      position: vscode.Position, lineText: string): vscode.Location[] | undefined {
    const reference = pathReferenceAt(lineText, position.character);
    if (reference === undefined) {
      return undefined;
    }
    if (reference.step !== null) {
      const line = stepLineOf(allLines(document), reference.step);
      return line === undefined
          ? undefined
          : [new vscode.Location(document.uri, new vscode.Position(line, 0))];
    }
    const locations: vscode.Location[] = [];
    for (const route of sourceScope(home, symbols, document)) {
      const source = route.sources.find((candidate) => candidate.name === reference.root);
      if (source !== undefined) {
        locations.push(new vscode.Location(
            vscode.Uri.file(path.join(home, ...route.source.split('/'))),
            new vscode.Position((source.line ?? 1) - 1, 0)));
      }
    }
    return locations.length === 0 ? undefined : locations;
  }
}

/** Every line of the document — the step scan reads the whole sequence. */
function allLines(document: vscode.TextDocument): string[] {
  return linesAbove(document, document.lineCount);
}

export class SymbolCompletionProvider implements vscode.CompletionItemProvider {
  constructor(private readonly index: SymbolIndex) {}

  provideCompletionItems(document: vscode.TextDocument, position: vscode.Position):
      vscode.CompletionItem[] | undefined {
    const found = this.index.symbolsFor(document.uri.fsPath);
    if (found === undefined) {
      return undefined;
    }
    const lineText = document.lineAt(position.line).text;
    const kind = completionKindAt(lineText, position.character);
    if (kind === undefined) {
      return this.sourceCompletions(found.home, found.symbols, document, position, lineText);
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

  /**
   * After `source:` at a reference position: the names the binding routes declare, one
   * item per name (decision 5), each saying its arm, its file and its route. `main` is
   * offered only where a route declares it.
   */
  private sourceCompletions(home: string, symbols: AppSymbols, document: vscode.TextDocument,
      position: vscode.Position, lineText: string): vscode.CompletionItem[] | undefined {
    if (!sourceCompletionAt(document.uri.fsPath, lineText, position.character,
        linesAbove(document, position.line))) {
      return this.pathCompletions(home, symbols, document, position, lineText);
    }
    const items = new Map<string, vscode.CompletionItem>();
    for (const route of sourceScope(home, symbols, document)) {
      for (const source of route.sources) {
        if (items.has(source.name)) {
          continue;
        }
        const item = new vscode.CompletionItem(source.name, vscode.CompletionItemKind.Value);
        item.detail = sourceDetail(source, route);
        items.set(source.name, item);
      }
    }
    return [...items.values()];
  }

  /**
   * A bindable path being typed: after `steps.`, the steps declared above the cursor (in a
   * job's pipeline the earlier ones, which is all a reference may name); at a root under
   * `model:`/`body:`/`payload:`/`params:`, the route's declared sources and — when the
   * document declares steps — `steps`.
   */
  private pathCompletions(home: string, symbols: AppSymbols, document: vscode.TextDocument,
      position: vscode.Position, lineText: string): vscode.CompletionItem[] | undefined {
    const above = linesAbove(document, position.line);
    const context = pathCompletionAt(lineText, position.character, above);
    if (context === undefined) {
      return undefined;
    }
    if (context.kind === 'step') {
      return stepsDeclaredAbove(above, above.length).map((id) => {
        const item = new vscode.CompletionItem(id, vscode.CompletionItemKind.Reference);
        item.detail = 'step';
        return item;
      });
    }
    const items = new Map<string, vscode.CompletionItem>();
    for (const route of sourceScope(home, symbols, document)) {
      for (const source of route.sources) {
        if (items.has(source.name)) {
          continue;
        }
        const item = new vscode.CompletionItem(source.name, vscode.CompletionItemKind.Value);
        item.detail = sourceDetail(source, route);
        items.set(source.name, item);
      }
    }
    if (stepsDeclaredAbove(allLines(document), document.lineCount).length > 0) {
      const item = new vscode.CompletionItem('steps', vscode.CompletionItemKind.Module);
      item.detail = 'the step results, steps.<id>';
      items.set('steps', item);
    }
    return items.size === 0 ? undefined : [...items.values()];
  }
}
