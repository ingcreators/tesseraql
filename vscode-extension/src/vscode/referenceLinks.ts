import * as fs from 'node:fs';
import * as path from 'node:path';
import * as vscode from 'vscode';
import { referenceLinks } from '../core/links';

/**
 * Makes `file:`/`view:`/`template:` values clickable, resolved against the document's
 * directory. A link appears only when the target exists — a broken reference stays a
 * lint finding, not a dead link.
 */
export class ReferenceLinkProvider implements vscode.DocumentLinkProvider {
  provideDocumentLinks(document: vscode.TextDocument): vscode.DocumentLink[] {
    const dir = path.dirname(document.uri.fsPath);
    const links: vscode.DocumentLink[] = [];
    for (const reference of referenceLinks(document.getText())) {
      const target = path.join(dir, ...reference.target.split('/'));
      if (!fs.existsSync(target)) {
        continue;
      }
      const link = new vscode.DocumentLink(
          new vscode.Range(reference.line, reference.start, reference.line, reference.end),
          vscode.Uri.file(target));
      link.tooltip = 'Open ' + reference.target;
      links.push(link);
    }
    return links;
  }
}
