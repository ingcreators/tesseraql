import * as path from 'node:path';
import * as vscode from 'vscode';
import { AppNode, buildAppTree } from '../core/tree';

/**
 * The TesseraQL explorer: routes by kind, views, migrations, and test suites, straight
 * from the app layout (docs/app-layout.md) — one click to the source, no CLI call.
 */
export class AppExplorer implements vscode.TreeDataProvider<AppNode> {
  private readonly changed = new vscode.EventEmitter<void>();
  readonly onDidChangeTreeData = this.changed.event;

  constructor(private homes: readonly string[]) {}

  setHomes(homes: readonly string[]): void {
    this.homes = homes;
    this.refresh();
  }

  refresh(): void {
    this.changed.fire();
  }

  getTreeItem(node: AppNode): vscode.TreeItem {
    const item = new vscode.TreeItem(node.label,
        node.kind === 'file'
            ? vscode.TreeItemCollapsibleState.None
            : vscode.TreeItemCollapsibleState.Collapsed);
    if (node.kind === 'section') {
      item.iconPath = new vscode.ThemeIcon(SECTION_ICONS[node.label] ?? 'folder');
    } else if (node.path !== undefined) {
      item.resourceUri = vscode.Uri.file(node.path);
      if (node.kind === 'file') {
        item.contextValue = 'file';
        item.command = {
          command: 'vscode.open',
          title: 'Open',
          arguments: [vscode.Uri.file(node.path)],
        };
      }
    }
    return item;
  }

  getChildren(node?: AppNode): AppNode[] {
    if (node !== undefined) {
      return node.children;
    }
    if (this.homes.length === 1) {
      return buildAppTree(this.homes[0]);
    }
    return this.homes.map((home) => ({
      label: path.basename(home),
      kind: 'section' as const,
      path: home,
      children: buildAppTree(home),
    }));
  }
}

const SECTION_ICONS: Record<string, string> = {
  Routes: 'symbol-interface',
  Views: 'preview',
  Migrations: 'database',
  Tests: 'beaker',
};
