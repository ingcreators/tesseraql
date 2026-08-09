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

/** One registered view document: its id, home-relative source path, and the id line. */
export interface ViewDocument {
  id: string;
  source: string;
  /** 0-based line of the explicit top-level `id:` (0 when filename-derived). */
  idLine: number;
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
  return viewIdInfoOf(fileName, content).id;
}

/** {@link viewIdOf} plus the 0-based line the id was declared on (0 when derived). */
export function viewIdInfoOf(fileName: string, content: string):
    { id: string; idLine: number } {
  const lines = content.split('\n');
  for (let index = 0; index < lines.length; index++) {
    const match = /^id:\s*(["']?)([^\s#"']+)\1\s*(?:#.*)?$/u.exec(lines[index]);
    if (match !== null) {
      return { id: match[2], idLine: index };
    }
  }
  const id = fileName.endsWith(VIEW_SUFFIX)
      ? fileName.slice(0, fileName.length - VIEW_SUFFIX.length)
      : fileName;
  return { id, idLine: 0 };
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
      const { id, idLine } = viewIdInfoOf(path.basename(file), content);
      if (seen.has(id)) {
        continue;
      }
      seen.add(id);
      views.push({ id, idLine, source: [root, ...entry.split(path.sep)].join('/') });
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
 * Whether a `- <id>` line is an item of a `views:` block sequence — the alternative
 * spelling of the wave-2c binding list. Line-based like every context here: walk up
 * past blank/comment lines and same-indent sibling items; the first other line must
 * be a less-indented `views:` key.
 */
export function isViewsSequenceItem(lineText: string,
    linesAbove: readonly string[]): boolean {
  if (!/^\s*-\s*["']?[\p{L}\p{N}_.-]*$/u.test(lineText)) {
    return false;
  }
  const itemIndent = /^\s*/.exec(lineText)![0].length;
  for (let index = linesAbove.length - 1; index >= 0; index--) {
    const above = linesAbove[index];
    if (/^\s*(#|$)/.test(above)) {
      continue;
    }
    if (/^\s*-\s/.test(above)) {
      if (/^\s*/.exec(above)![0].length === itemIndent) {
        continue;
      }
      return false;
    }
    const key = /^(\s*)views:\s*(#.*)?$/.exec(above);
    return key !== null && key[1].length < itemIndent;
  }
  return false;
}

/**
 * The completion context of a cursor inside the view-composition surface. `view:` and
 * `widget:` match in block form and inside flow maps (`- { type: view, view: … }`);
 * `views:` matches inside its flow list and — given {@code linesAbove} — as a block
 * sequence. Identifier runs are Unicode (docs/unicode-identifiers.md) — a half-typed
 * Japanese view id keeps its context.
 */
export function viewCompletionAt(lineText: string, character: number,
    linesAbove?: readonly string[]): ViewCompletionContext | undefined {
  const head = lineText.slice(0, character);
  // response.html.view:, a `type: view` panel entry, or a detail child.
  if (/(?:^\s*(?:-\s+)?|[{,]\s*)view:\s*["']?[\p{L}\p{N}_.-]*$/u.test(head)) {
    return { kind: 'view-id' };
  }
  // views: [a, b — the template-route binding list (view-composition wave 2c).
  if (/^\s*views:\s*\[(?:[^\]#]*,)?\s*["']?[\p{L}\p{N}_.-]*$/u.test(head)) {
    return { kind: 'view-id' };
  }
  // views: as a block sequence — `- <partial>` under a less-indented views: key.
  if (linesAbove !== undefined && /^\s*-\s*["']?[\p{L}\p{N}_.-]*$/u.test(head)
      && isViewsSequenceItem(head, linesAbove)) {
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

/** A view-id reference under the cursor, with its exact span. */
export interface ViewReference {
  id: string;
  start: number;
  end: number;
}

/**
 * The view-id reference the cursor sits on — the go-to-definition counterpart of
 * {@link viewCompletionAt}: `view: <id>` (block form or inside a flow map), an id
 * inside `views: [ ... ]`, or a `- <id>` item of a `views:` block sequence.
 */
export function viewReferenceAt(lineText: string, character: number,
    linesAbove?: readonly string[]): ViewReference | undefined {
  // view: <id> — anywhere on the line, but never a longer key like preview:.
  for (const match of lineText.matchAll(
      /(?<![\p{L}\p{N}_.-])view:\s*(["']?)([\p{L}\p{N}_.-]+)\1/gu)) {
    const start = match.index + match[0].length - match[2].length - match[1].length;
    const end = start + match[2].length;
    if (character >= start && character <= end) {
      return { id: match[2], start, end };
    }
  }
  const flowList = /^(\s*views:\s*\[)/.exec(lineText);
  if (flowList !== null) {
    for (const match of lineText.slice(flowList[1].length)
        .matchAll(/[\p{L}\p{N}_.-]+/gu)) {
      const start = flowList[1].length + match.index;
      const end = start + match[0].length;
      if (character >= start && character <= end) {
        return { id: match[0], start, end };
      }
    }
    return undefined;
  }
  if (linesAbove !== undefined && isViewsSequenceItem(lineText, linesAbove)) {
    const item = /^(\s*-\s*["']?)([\p{L}\p{N}_.-]+)/u.exec(lineText);
    if (item !== null) {
      const start = item[1].length;
      const end = start + item[2].length;
      if (character >= start && character <= end) {
        return { id: item[2], start, end };
      }
    }
  }
  return undefined;
}
