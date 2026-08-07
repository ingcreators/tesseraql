// Completion inside HTML mail templates (docs/notifications.md "HTML mail"): the
// tql/email fragment palette after `~{tql/email/<library> :: `, and the mail render
// model (`payload`/`event`, event members) inside `${...}` expressions. Registered for
// html documents in a TesseraQL workspace; lines without the mail markers complete
// nothing, so ordinary page templates are untouched.
import * as vscode from 'vscode';
import {
  EMAIL_FRAGMENTS,
  EVENT_MEMBERS,
  MAIL_MODEL_ROOTS,
  emailCompletionAt,
} from '../core/emailFragments';

export class EmailFragmentCompletionProvider implements vscode.CompletionItemProvider {
  provideCompletionItems(document: vscode.TextDocument, position: vscode.Position):
      vscode.CompletionItem[] | undefined {
    const context = emailCompletionAt(
        document.lineAt(position.line).text, position.character);
    if (context === undefined) {
      return undefined;
    }
    if (context.kind === 'fragment') {
      return EMAIL_FRAGMENTS.filter((fragment) => fragment.library === context.library)
          .map((fragment) => {
            const item = new vscode.CompletionItem(fragment.name,
                vscode.CompletionItemKind.Function);
            item.detail = fragment.params.length === 0
                ? fragment.name
                : `${fragment.name}(${fragment.params.join(', ')})`;
            item.insertText = fragment.params.length === 0
                ? fragment.name
                : new vscode.SnippetString(`${fragment.name}(${fragment.params
                    .map((param, index) => `\${${index + 1}:${param}}`).join(', ')})`);
            return item;
          });
    }
    if (context.kind === 'event-member') {
      return EVENT_MEMBERS.map((member) =>
        new vscode.CompletionItem(member, vscode.CompletionItemKind.Property));
    }
    return MAIL_MODEL_ROOTS.map((root) => {
      const item = new vscode.CompletionItem(root, vscode.CompletionItemKind.Variable);
      item.detail = root === 'payload'
          ? 'the notification payload'
          : 'event: id, source, app';
      return item;
    });
  }
}
