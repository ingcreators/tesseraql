// Completion for the view-composition surface (docs/view-composition.md, finishing
// wave): view ids at the reference positions (`response.html.view:`, `views: [..]`,
// panel/child `view:`) from a workspace scan of the app's view registry, plus the
// `shell:` negotiation vocabulary and the `widget:` enum. The scan mirrors
// ManifestLoader.loadViews — the `tesseraql symbols` contract does not carry views,
// so the editor reads the `*.view.yml` documents directly.
import * as path from 'node:path';
import * as vscode from 'vscode';
import { homeOf } from '../core/appHome';
import {
  SHELL_MODES, WIDGETS, scanViewDocuments, viewCompletionAt, viewReferenceAt,
} from '../core/views';

/** The lines before the cursor's — the block-sequence contexts read upward. */
function linesAbove(document: vscode.TextDocument, line: number): string[] {
  const lines: string[] = [];
  for (let index = 0; index < line; index++) {
    lines.push(document.lineAt(index).text);
  }
  return lines;
}

export class ViewIntelCompletionProvider implements vscode.CompletionItemProvider {
  constructor(private readonly homes: () => readonly string[]) {}

  provideCompletionItems(document: vscode.TextDocument, position: vscode.Position):
      vscode.CompletionItem[] | undefined {
    const context = viewCompletionAt(document.lineAt(position.line).text, position.character,
        linesAbove(document, position.line));
    if (context === undefined) {
      return undefined;
    }
    if (context.kind === 'shell') {
      return SHELL_MODES.map((mode) => {
        const item = new vscode.CompletionItem(mode.name, vscode.CompletionItemKind.EnumMember);
        item.detail = mode.detail;
        return item;
      });
    }
    if (context.kind === 'widget') {
      return WIDGETS.map((widget) =>
        new vscode.CompletionItem(widget, vscode.CompletionItemKind.EnumMember));
    }
    const home = homeOf(document.uri.fsPath, this.homes());
    if (home === undefined) {
      return undefined;
    }
    return scanViewDocuments(home).map((view) => {
      const item = new vscode.CompletionItem(view.id, vscode.CompletionItemKind.Reference);
      item.detail = view.source;
      return item;
    });
  }
}

/**
 * Go-to-definition for view-id references (`view:`, `views:` flow list and block
 * sequence): jumps to the referenced `*.view.yml` document, landing on its `id:` line
 * (or the top when the id is filename-derived). Resolution mirrors the manifest
 * registry via the same workspace scan the completion uses.
 */
export class ViewDefinitionProvider implements vscode.DefinitionProvider {
  constructor(private readonly homes: () => readonly string[]) {}

  provideDefinition(document: vscode.TextDocument, position: vscode.Position):
      vscode.Location | undefined {
    const reference = viewReferenceAt(document.lineAt(position.line).text,
        position.character, linesAbove(document, position.line));
    if (reference === undefined) {
      return undefined;
    }
    const home = homeOf(document.uri.fsPath, this.homes());
    if (home === undefined) {
      return undefined;
    }
    const target = scanViewDocuments(home).find((view) => view.id === reference.id);
    if (target === undefined) {
      return undefined;
    }
    return new vscode.Location(
        vscode.Uri.file(path.join(home, ...target.source.split('/'))),
        new vscode.Position(target.idLine, 0));
  }
}
