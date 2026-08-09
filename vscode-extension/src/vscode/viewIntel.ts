// Completion for the view-composition surface (docs/view-composition.md, finishing
// wave): view ids at the reference positions (`response.html.view:`, `views: [..]`,
// panel/child `view:`) from a workspace scan of the app's view registry, plus the
// `shell:` negotiation vocabulary and the `widget:` enum. The scan mirrors
// ManifestLoader.loadViews — the `tesseraql symbols` contract does not carry views,
// so the editor reads the `*.view.yml` documents directly.
import * as vscode from 'vscode';
import { homeOf } from '../core/appHome';
import { SHELL_MODES, WIDGETS, scanViewDocuments, viewCompletionAt } from '../core/views';

export class ViewIntelCompletionProvider implements vscode.CompletionItemProvider {
  constructor(private readonly homes: () => readonly string[]) {}

  provideCompletionItems(document: vscode.TextDocument, position: vscode.Position):
      vscode.CompletionItem[] | undefined {
    const context = viewCompletionAt(document.lineAt(position.line).text, position.character);
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
