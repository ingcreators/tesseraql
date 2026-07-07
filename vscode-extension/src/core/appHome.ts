import * as fs from 'node:fs';
import * as path from 'node:path';

// App-home discovery (docs/vscode-extension.md): a workspace folder — or a direct
// child directory — holding config/tesseraql.yml is an app home.

export function isAppHome(dir: string): boolean {
  try {
    return fs.statSync(path.join(dir, 'config', 'tesseraql.yml')).isFile();
  } catch {
    return false;
  }
}

const SKIPPED_CHILDREN = new Set(['node_modules', 'target', 'work', 'out', 'dist']);

export function findAppHomes(roots: readonly string[]): string[] {
  const homes: string[] = [];
  for (const root of roots) {
    if (isAppHome(root)) {
      homes.push(root);
      continue;
    }
    let entries: fs.Dirent[];
    try {
      entries = fs.readdirSync(root, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const entry of entries) {
      if (!entry.isDirectory() || entry.name.startsWith('.') || SKIPPED_CHILDREN.has(entry.name)) {
        continue;
      }
      const child = path.join(root, entry.name);
      if (isAppHome(child)) {
        homes.push(child);
      }
    }
  }
  return homes.sort();
}

/** The app home containing {@code file}, or undefined when it is outside every home. */
export function homeOf(file: string, homes: readonly string[]): string | undefined {
  return homes.find((home) => file === home || file.startsWith(home + path.sep));
}
