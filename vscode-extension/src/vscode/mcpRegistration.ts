import * as fs from 'node:fs';
import * as path from 'node:path';
import * as vscode from 'vscode';
import { McpMergeResult, mergeClaudeMcp, mergeVsCodeMcp } from '../core/mcpConfig';

/**
 * TesseraQL: Register MCP Server — writes the Phase 24 dev-tools server into the
 * chosen client configuration(s) in the app home, merging with existing servers and
 * never overwriting a foreign `tesseraql` entry without confirmation.
 */
export async function registerMcpServer(home: string): Promise<void> {
  const cliPath = vscode.workspace.getConfiguration('tesseraql').get<string>('cliPath', 'tesseraql');
  const targets: Array<{ label: string; file: string; merge: (existing: string | undefined) => McpMergeResult }> = [
    {
      label: 'VS Code (.vscode/mcp.json)',
      file: path.join(home, '.vscode', 'mcp.json'),
      merge: (existing) => mergeVsCodeMcp(existing, cliPath),
    },
    {
      label: 'Claude Code (.mcp.json)',
      file: path.join(home, '.mcp.json'),
      merge: (existing) => mergeClaudeMcp(existing, cliPath),
    },
  ];
  const picked = await vscode.window.showQuickPick(targets.map((target) => target.label),
      { canPickMany: true, placeHolder: 'Register the TesseraQL dev-tools MCP server for…' });
  if (picked === undefined || picked.length === 0) {
    return;
  }
  const written: string[] = [];
  const skipped: string[] = [];
  for (const target of targets.filter((candidate) => picked.includes(candidate.label))) {
    const existing = fs.existsSync(target.file) ? fs.readFileSync(target.file, 'utf8') : undefined;
    const result = target.merge(existing);
    if (result.unparseable) {
      // Nothing was merged, so there is nothing safe to write: rewriting from an empty root would
      // drop every other server the file declares. Hand the file to the user with the entry to add.
      const open = await vscode.window.showWarningMessage(
          `${path.basename(target.file)} is not valid JSON (comments and trailing commas are `
              + `common in mcp.json), so the 'tesseraql' entry cannot be merged without losing `
              + `the rest of the file. Add it by hand.`,
          'Open File');
      if (open === 'Open File') {
        void vscode.window.showTextDocument(vscode.Uri.file(target.file));
      }
      skipped.push(`${target.label}: not valid JSON`);
      continue;
    }
    if (!result.changed) {
      written.push(`${target.label}: already registered`);
      continue;
    }
    if (result.conflict) {
      const answer = await vscode.window.showWarningMessage(
          `${path.basename(target.file)} already has a different 'tesseraql' server entry. Overwrite it?`,
          { modal: true }, 'Overwrite');
      if (answer !== 'Overwrite') {
        continue;
      }
    }
    fs.mkdirSync(path.dirname(target.file), { recursive: true });
    fs.writeFileSync(target.file, result.content);
    written.push(target.label);
  }
  if (written.length > 0 || skipped.length > 0) {
    void vscode.window.showInformationMessage(
        'TesseraQL MCP server: ' + written.concat(skipped).join('; '));
  }
}
