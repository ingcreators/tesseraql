import * as vscode from 'vscode';
import { errorCodeDocsUrl, TQL_CODE_PATTERN } from '../core/errorCodes';

/** Hovering a TQL-<DOMAIN>-<n> literal links to the published error-code reference. */
export class ErrorCodeHoverProvider implements vscode.HoverProvider {
  provideHover(document: vscode.TextDocument, position: vscode.Position): vscode.Hover | undefined {
    const range = document.getWordRangeAtPosition(position, TQL_CODE_PATTERN);
    if (range === undefined) {
      return undefined;
    }
    const code = document.getText(range);
    const url = errorCodeDocsUrl(code);
    if (url === undefined) {
      return undefined;
    }
    return new vscode.Hover(
        new vscode.MarkdownString(`[\`${code}\` — TesseraQL error-code reference](${url})`),
        range);
  }
}
