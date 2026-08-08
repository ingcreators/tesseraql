// View-composition intel (docs/view-composition.md, finishing wave): the app-wide
// view registry the manifest loader indexes — every `*.view.yml` under `web/` and
// `templates/`, id explicit or filename-derived — mirrored here by a workspace scan,
// because the `tesseraql symbols` contract does not carry views. On top of the scan:
// the completion contexts for the id-reference positions (`response.html.view:`,
// `views: [..]` on template routes, `view:` on dashboard panels and detail children),
// the `shell:` negotiation vocabulary, and the `widget:` enum shared by view fields
// and field domains.

import * as fs from 'node:fs';
import * as path from 'node:path';

/** One registered view document: its id and the home-relative source path. */
export interface ViewDocument {
  id: string;
  source: string;
}

const VIEW_SUFFIX = '.view.yml';

/** The registry roots ManifestLoader.loadViews walks. */
const VIEW_ROOTS = ['web', 'templates'] as const;

/**
 * The document's id exactly as ViewSpec.parse resolves it: an explicit top-level
 * `id:`, else the file name minus `.view.yml`. Line-based on the column-0 key —
 * no YAML semantics enter the extension.
 */
export function viewIdOf(fileName: string, content: string): string {
  for (const line of content.split('\n')) {
    const match = /^id:\s*(["']?)([^\s#"']+)\1\s*(?:#.*)?$/u.exec(line);
    if (match !== null) {
      return match[2];
    }
  }
  return fileName.endsWith(VIEW_SUFFIX)
      ? fileName.slice(0, fileName.length - VIEW_SUFFIX.length)
      : fileName;
}

/**
 * Scans an app home the way the manifest loader does: `*.view.yml` under `web/` and
 * `templates/`, sorted. The first document claims an id — a duplicate is the build's
 * finding (TQL-VIEW-3315), not the editor's; completion offers each id once.
 */
export function scanViewDocuments(home: string): ViewDocument[] {
  const views: ViewDocument[] = [];
  const seen = new Set<string>();
  for (const root of VIEW_ROOTS) {
    const tree = path.join(home, root);
    let entries: string[];
    try {
      entries = (fs.readdirSync(tree, { recursive: true }) as string[])
          .filter((entry) => entry.endsWith(VIEW_SUFFIX)).sort();
    } catch {
      continue;
    }
    for (const entry of entries) {
      const file = path.join(tree, entry);
      let content: string;
      try {
        if (!fs.statSync(file).isFile()) {
          continue;
        }
        content = fs.readFileSync(file, 'utf8');
      } catch {
        continue;
      }
      const id = viewIdOf(path.basename(file), content);
      if (seen.has(id)) {
        continue;
      }
      seen.add(id);
      views.push({ id, source: [root, ...entry.split(path.sep)].join('/') });
    }
  }
  return views;
}

/** The `response.html.shell:` negotiation vocabulary (view-composition wave 2a). */
export const SHELL_MODES = [
  {
    name: 'auto',
    detail: 'negotiate per request: fragment for HX-Request, shell-wrapped page for '
        + 'direct navigation (default; Vary: HX-Request)',
  },
  { name: 'always', detail: 'always the shell-wrapped page' },
  { name: 'never', detail: 'always the bare fragment — an htmx-only region endpoint' },
] as const;

/** The widget vocabulary (ViewSpec.WIDGETS, TQL-VIEW-3305) — view fields and domain fields. */
export const WIDGETS = [
  'text', 'textarea', 'number', 'date', 'datetime-local', 'checkbox', 'select', 'hidden',
] as const;

export type ViewCompletionContext =
  | { kind: 'view-id' }
  | { kind: 'shell' }
  | { kind: 'widget' };

/**
 * The completion context of a cursor inside the view-composition surface. `view:` and
 * `widget:` match in block form and inside flow maps (`- { type: view, view: … }`);
 * `views:` matches inside its flow list. Identifier runs are Unicode
 * (docs/unicode-identifiers.md) — a half-typed Japanese view id keeps its context.
 */
export function viewCompletionAt(lineText: string, character: number):
    ViewCompletionContext | undefined {
  const head = lineText.slice(0, character);
  // response.html.view:, a `type: view` panel entry, or a detail child.
  if (/(?:^\s*(?:-\s+)?|[{,]\s*)view:\s*["']?[\p{L}\p{N}_.-]*$/u.test(head)) {
    return { kind: 'view-id' };
  }
  // views: [a, b — the template-route binding list (view-composition wave 2c).
  if (/^\s*views:\s*\[(?:[^\]#]*,)?\s*["']?[\p{L}\p{N}_.-]*$/u.test(head)) {
    return { kind: 'view-id' };
  }
  if (/^\s*shell:\s*["']?[a-z]*$/.test(head)) {
    return { kind: 'shell' };
  }
  if (/(?:^\s*(?:-\s+)?|[{,]\s*)widget:\s*["']?[a-z-]*$/.test(head)) {
    return { kind: 'widget' };
  }
  return undefined;
}
