import * as fs from 'node:fs';
import * as path from 'node:path';

// The explorer model (docs/vscode-extension.md): built from the documented app layout
// (docs/app-layout.md) — route trees by kind, views, migration scripts, test suites.
// Pure filesystem reads, no CLI call, so navigation works even when lint cannot run.

export type NodeKind = 'section' | 'directory' | 'file';

export interface AppNode {
  label: string;
  kind: NodeKind;
  /** Absolute path for files (and directories); sections have none. */
  path?: string;
  children: AppNode[];
}

const ROUTE_KINDS = ['web', 'consume', 'batch', 'mcp'];

export function buildAppTree(home: string): AppNode[] {
  const sections: AppNode[] = [];

  const routeKinds = ROUTE_KINDS.map((kind) => directoryNode(path.join(home, kind), kind, isRouteFile))
    .filter((node): node is AppNode => node !== undefined);
  if (routeKinds.length > 0) {
    sections.push({ label: 'Routes', kind: 'section', children: routeKinds });
  }

  const views = collectFiles(home, (name) => name.endsWith('.view.yml'));
  if (views.length > 0) {
    sections.push({ label: 'Views', kind: 'section', children: views.map((file) => fileNode(home, file)) });
  }

  const migrations = collectFiles(path.join(home, 'db'), (name) => name.endsWith('.sql'));
  if (migrations.length > 0) {
    sections.push({
      label: 'Migrations',
      kind: 'section',
      children: migrations.map((file) => fileNode(path.join(home, 'db'), file)),
    });
  }

  const tests = collectFiles(path.join(home, 'tests'), (name) => name.endsWith('.yml'));
  if (tests.length > 0) {
    sections.push({
      label: 'Tests',
      kind: 'section',
      children: tests.map((file) => fileNode(path.join(home, 'tests'), file)),
    });
  }

  return sections;
}

function isRouteFile(name: string): boolean {
  return name.endsWith('.yml') && !name.endsWith('.view.yml');
}

/** A recursive directory node, or undefined when nothing under it matches. */
function directoryNode(dir: string, label: string, matches: (name: string) => boolean): AppNode | undefined {
  let entries: fs.Dirent[];
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch {
    return undefined;
  }
  const directories: AppNode[] = [];
  const files: AppNode[] = [];
  for (const entry of entries.sort((a, b) => a.name.localeCompare(b.name))) {
    if (entry.name.startsWith('.')) {
      continue;
    }
    if (entry.isDirectory()) {
      const child = directoryNode(path.join(dir, entry.name), entry.name, matches);
      if (child !== undefined) {
        directories.push(child);
      }
    } else if (matches(entry.name)) {
      files.push({ label: entry.name, kind: 'file', path: path.join(dir, entry.name), children: [] });
    }
  }
  if (directories.length === 0 && files.length === 0) {
    return undefined;
  }
  return { label, kind: 'directory', path: dir, children: [...directories, ...files] };
}

/** All matching files under {@code root}, as sorted root-relative paths. */
function collectFiles(root: string, matches: (name: string) => boolean): string[] {
  const found: string[] = [];
  walk(root, '', matches, found);
  return found.sort();
}

function walk(dir: string, prefix: string, matches: (name: string) => boolean, found: string[]): void {
  let entries: fs.Dirent[];
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch {
    return;
  }
  for (const entry of entries) {
    if (entry.name.startsWith('.') || entry.name === 'node_modules' || entry.name === 'work') {
      continue;
    }
    const relative = prefix === '' ? entry.name : prefix + '/' + entry.name;
    if (entry.isDirectory()) {
      walk(path.join(dir, entry.name), relative, matches, found);
    } else if (matches(entry.name)) {
      found.push(relative);
    }
  }
}

function fileNode(root: string, relative: string): AppNode {
  return { label: relative, kind: 'file', path: path.join(root, ...relative.split('/')), children: [] };
}
