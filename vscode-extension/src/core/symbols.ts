// The symbols contract (docs/vscode-extension.md, Phase 56 slice 5): what the
// framework declares — policies, default-locale message keys, shared field domains
// and validation rules, routes — with source lines, as `tesseraql symbols` prints
// it. The providers navigate and complete over it; unknown references stay lint
// findings.

export interface DeclaredSymbol {
  name: string;
  source: string;
  line: number | null;
}

/** A declared workflow: its file plus the transition and dispatch ids it mounts. */
export interface WorkflowSymbol {
  name: string;
  source: string;
  line: number | null;
  transitions: string[];
  dispatches: string[];
}

/**
 * A named source a route declares (docs/unified-sources.md): its name, the line of its
 * `sources.<name>:` key (null for a flow-form block), the arm it carries, and the
 * `sql` arm's file.
 */
export interface SourceSymbol {
  name: string;
  line: number | null;
  arm: string | null;
  file: string | null;
}

/**
 * A mounted route as the manifest resolves it: the file plus its served identity, the
 * named sources it declares in authored order, the view documents it binds — the one
 * `response.html.view` names and the ones a template route's `views:` composes
 * (docs/editor-named-sources.md) — and the views those documents embed, which read this
 * route's sources too (docs/view-composition.md). All four are empty on a pre-0.18 CLI
 * (`embeds` on one before the embeds rode along).
 */
export interface RouteSymbol {
  id: string | null;
  source: string;
  method: string | null;
  path: string | null;
  recipe: string | null;
  sources: SourceSymbol[];
  view: string | null;
  views: string[];
  embeds: string[];
}

/** A declared batch job: its file plus the one-line trigger story. */
export interface JobSymbol {
  name: string;
  source: string;
  line: number | null;
  trigger: string | null;
}

/** A document the CLI could not parse, so its symbols are missing from this index. */
export interface BrokenDocument {
  source: string;
  error: string;
}

export interface AppSymbols {
  policies: DeclaredSymbol[];
  messages: DeclaredSymbol[];
  domains: DeclaredSymbol[];
  rules: DeclaredSymbol[];
  decisions: DeclaredSymbol[];
  calendars: DeclaredSymbol[];
  catalogs: DeclaredSymbol[];
  routes: RouteSymbol[];
  workflows: WorkflowSymbol[];
  jobs: JobSymbol[];
  broken: BrokenDocument[];
}

export class SymbolsContractError extends Error {}

export function parseAppSymbols(stdout: string): AppSymbols {
  let parsed: unknown;
  try {
    parsed = JSON.parse(stdout);
  } catch {
    throw new SymbolsContractError('stdout is not JSON');
  }
  if (typeof parsed !== 'object' || parsed === null
      || !Array.isArray((parsed as any).policies) || !Array.isArray((parsed as any).messages)) {
    throw new SymbolsContractError('stdout is JSON but not the symbols document');
  }
  const document = parsed as {
    policies: unknown[]; messages: unknown[]; domains?: unknown; rules?: unknown;
    decisions?: unknown; calendars?: unknown; catalogs?: unknown; routes?: unknown;
    workflows?: unknown;
    jobs?: unknown; broken?: unknown;
  };
  return {
    policies: document.policies.map((value) => toSymbol(value, 'name')),
    messages: document.messages.map((value) => toSymbol(value, 'key')),
    // Absent on a pre-0.8 CLI (decisions: pre-0.9) — the shared-definition arrays
    // degrade to empty, not to a contract error, so lint and policy/message
    // intelligence keep working.
    domains: optionalSymbols(document.domains),
    rules: optionalSymbols(document.rules),
    decisions: optionalSymbols(document.decisions),
    // Absent on a pre-0.10 CLI (batch-platform track B): degrades to empty, same rule.
    calendars: optionalSymbols(document.calendars),
    // Absent on a pre-0.14 CLI (code catalogs): degrades to empty, same rule.
    catalogs: optionalSymbols(document.catalogs),
    routes: optionalRoutes(document.routes),
    // Absent on a pre-0.10 CLI: workflows degrade to empty, same rule.
    workflows: optionalWorkflows(document.workflows),
    jobs: optionalJobs(document.jobs),
    // Absent on a CLI that loaded strictly (it would have failed the whole run instead of
    // reporting skipped documents): degrades to empty, same rule.
    broken: optionalBroken(document.broken),
  };
}

function optionalBroken(value: unknown): BrokenDocument[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
      .map((entry) => entry as Record<string, unknown>)
      .filter((entry) => typeof entry?.source === 'string')
      .map((entry) => ({
        source: entry.source as string,
        error: typeof entry.error === 'string' ? entry.error : '',
      }));
}

function optionalJobs(value: unknown): JobSymbol[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.map((entry) => {
    const base = toSymbol(entry, 'id');
    const job = entry as Record<string, unknown>;
    return {
      ...base,
      trigger: typeof job.trigger === 'string' ? job.trigger : null,
    };
  });
}

function optionalWorkflows(value: unknown): WorkflowSymbol[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.map((entry) => {
    const base = toSymbol(entry, 'id');
    const workflow = entry as Record<string, unknown>;
    return {
      ...base,
      transitions: stringList(workflow.transitions),
      dispatches: stringList(workflow.dispatches),
    };
  });
}

function stringList(value: unknown): string[] {
  return Array.isArray(value)
      ? value.filter((entry): entry is string => typeof entry === 'string')
      : [];
}

function optionalSymbols(value: unknown): DeclaredSymbol[] {
  return Array.isArray(value) ? value.map((entry) => toSymbol(entry, 'name')) : [];
}

function optionalRoutes(value: unknown): RouteSymbol[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.map((entry) => {
    if (typeof entry !== 'object' || entry === null) {
      throw new SymbolsContractError('a route is not an object');
    }
    const route = entry as Record<string, unknown>;
    if (typeof route.source !== 'string') {
      throw new SymbolsContractError("a route lacks 'source'");
    }
    return {
      id: stringOrNull(route.id),
      source: route.source,
      method: stringOrNull(route.method),
      path: stringOrNull(route.path),
      recipe: stringOrNull(route.recipe),
      // Absent on a pre-0.18 CLI: the sources and view bindings degrade to empty, so
      // source navigation stays silent rather than failing the whole index.
      sources: optionalSources(route.sources),
      view: stringOrNull(route.view),
      views: stringList(route.views),
      embeds: stringList(route.embeds),
    };
  });
}

function optionalSources(value: unknown): SourceSymbol[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
      .map((entry) => entry as Record<string, unknown>)
      .filter((entry) => typeof entry?.name === 'string')
      .map((entry) => {
        const line = entry.line;
        return {
          name: entry.name as string,
          line: typeof line === 'number' && Number.isInteger(line) && line >= 1 ? line : null,
          arm: stringOrNull(entry.arm),
          file: stringOrNull(entry.file),
        };
      });
}

/**
 * The routes that bind a view document — through `response.html.view`, a template
 * route's `views:`, or by embedding it from a document they bind — which is where the
 * view's `source:` values are declared. The lint (TQL-VIEW-3308) judges a view against
 * the same routes: an embedded view reads the host route's sources, so each host is one
 * location. A route appears once however many ways it binds the view.
 */
export function routesBinding(symbols: AppSymbols, viewId: string): RouteSymbol[] {
  return symbols.routes.filter(
      (route) => route.view === viewId || route.views.includes(viewId)
          || route.embeds.includes(viewId));
}

/** The completion detail of a declared source: `sql · orders-by-state.sql · web/…/get.yml`. */
export function sourceDetail(source: SourceSymbol, route: RouteSymbol): string {
  return [source.arm, source.file, route.source]
      .filter((part): part is string => part !== null && part !== '')
      .join(' · ');
}

/** A named-source reference under the cursor, with its exact span. */
export interface SourceReference {
  value: string;
  /** 0-based columns of the value span. */
  start: number;
  end: number;
}

const VIEW_SUFFIX = '.view.yml';

/** Identifier runs are Unicode (docs/unicode-identifiers.md): a source may be named in Japanese. */
const SOURCE_NAME = '[\\p{L}\\p{N}_-]+';

const SOURCE_VALUE = new RegExp(
    `(?<![\\p{L}\\p{N}_.-])source:\\s*(["']?)(${SOURCE_NAME})\\1(?=[\\s,}]|$)`, 'gu');

/**
 * The named-source reference the cursor sits on (docs/editor-named-sources.md decision 2).
 * In a view document every scalar `source:` is one — the document's, a child's, a panel's,
 * in block form or inside a flow map. In a route document only a `source:` directly under an
 * `enrich:` entry is, and only when its value is a bare name: `steps.<id>` names a step, a
 * `params:` `source` is a bind name, a lookup's `source:` is a URL, a decision table's is a
 * block. Line-based like every context here; no YAML semantics enter the extension.
 */
export function sourceReferenceAt(fileName: string, lineText: string, character: number,
    linesAbove: readonly string[]): SourceReference | undefined {
  if (!isViewDocument(fileName) && !isEnrichmentSource(lineText, linesAbove)) {
    return undefined;
  }
  for (const match of lineText.matchAll(SOURCE_VALUE)) {
    const start = match.index + match[0].length - match[2].length - match[1].length;
    const end = start + match[2].length;
    if (character >= start && character <= end) {
      return { value: match[2], start, end };
    }
  }
  return undefined;
}

/**
 * Whether the cursor sits after a `source:` at one of the positions
 * {@link sourceReferenceAt} resolves — block form or inside a flow map — so completion
 * can offer the binding routes' declared names.
 */
export function sourceCompletionAt(fileName: string, lineText: string, character: number,
    linesAbove: readonly string[]): boolean {
  const head = lineText.slice(0, character);
  if (!/(?:^\s*(?:-\s+)?|[{,]\s*)source:\s*["']?[\p{L}\p{N}_-]*$/u.test(head)) {
    return false;
  }
  return isViewDocument(fileName) || isEnrichmentSource(lineText, linesAbove);
}

function isViewDocument(fileName: string): boolean {
  return fileName.endsWith(VIEW_SUFFIX);
}

/**
 * A bindable path under the cursor (docs/editor-named-sources.md, mechanism 3): a scalar
 * whose value is one dotted path — `users: main.rows`, `created: steps.main.affectedRows`,
 * `file: steps.report.transferId` — or a `{path}` placeholder inside one, as in
 * `location: /items/{steps.record.keys.id}`. The root is what the path is rooted in: a
 * source name the route declares, or `steps`, whose second segment names a step of the
 * same document.
 */
export interface PathReference {
  root: string;
  /** The step id a `steps.<id>…` path names; null for a source-rooted path. */
  step: string | null;
  /** 0-based columns of the whole path. */
  start: number;
  end: number;
}

/** Each segment of a path is an identifier run, Unicode like a source name. */
const PATH_SEGMENT = '[\\p{L}\\p{N}_-]+';

/** Every dotted run on a line — the candidates, before their position is read. */
const PATH_TOKEN = new RegExp(
    `(?<![\\p{L}\\p{N}_.\\-/])(${PATH_SEGMENT})((?:\\.${PATH_SEGMENT})+)(?![\\p{L}\\p{N}_.\\-/])`,
    'gu');

/** The text before a token when the token is a scalar's whole value: `key:` (block or flow). */
const SCALAR_VALUE_BEFORE =
    /(?:^|[{,])\s*(?:-\s+)?["']?([^\s:#"'{}\[\],]+)["']?:\s*["']?$/u;

/** The text after a token when the token ends the scalar: nothing, a flow separator, a comment. */
const SCALAR_VALUE_AFTER = /^["']?\s*(?:$|[,}]|#)/u;

/**
 * Keys whose dotted values are something else: another detector's reference (a message key,
 * a policy, a view id …) or a name that is never a path (a document or step id, a file). A
 * value with a file extension is a file wherever it sits.
 */
const NOT_A_PATH_KEY = new Set([
  'id', 'name', 'kind', 'recipe', 'version', 'view', 'views', 'template', 'policy', 'message',
  'title', 'label', 'domain', 'use', 'decision', 'workflow', 'calendar', 'after', 'codes',
  'url', 'path', 'datasource', 'format', 'transport', 'type', 'widget', 'shell', 'select',
  'column', 'x', 'y', 'when', 'rule', 'field', 'code', 'locale', 'zone', 'filename',
]);

const FILE_EXTENSION = /\.(sql|ya?ml|html?|jxls|xlsx|csv|pdf|json|txt|md|tpl)$/iu;

/**
 * The bindable path the cursor sits on, by the value's shape alone: no key list decides
 * (`model:`, `body:`, `payload:`, `params:`, `location:`, a push step's `file:`, a notify's
 * `attach:`, a chunk reader's `spool:` all carry one), only whether the line's value is a
 * dotted path — the whole scalar, or a `{…}` placeholder. Whether the root means anything is
 * the resolver's question: a declared source, or `steps` with a step the document declares.
 */
export function pathReferenceAt(lineText: string, character: number): PathReference | undefined {
  for (const match of lineText.matchAll(PATH_TOKEN)) {
    const start = match.index;
    const end = start + match[0].length;
    if (character < start || character > end) {
      continue;
    }
    if (FILE_EXTENSION.test(match[0])) {
      return undefined;
    }
    const before = lineText.slice(0, start);
    const after = lineText.slice(end);
    const placeholder = before.endsWith('{') && after.startsWith('}');
    const scalar = SCALAR_VALUE_BEFORE.exec(before);
    if (!placeholder) {
      if (scalar === null || !SCALAR_VALUE_AFTER.test(after) || NOT_A_PATH_KEY.has(scalar[1])) {
        return undefined;
      }
    }
    if (before.includes('#') && /(^|\s)#/.test(before)) {
      return undefined;
    }
    const segments = match[2].slice(1).split('.');
    return {
      root: match[1],
      step: match[1] === 'steps' ? segments[0] : null,
      start,
      end,
    };
  }
  return undefined;
}

/**
 * The 0-based line declaring step `id` in a document: the `- id: <id>` item (block form) or
 * the `- { id: <id>, … }` item (flow form) of a top-level `steps:` (a route) or `pipeline:` (a
 * job) sequence — never a nested `id:` (a field's, a view's) and never the document's own
 * top-level `id:`. The first declaration wins, as the runtime's id-keyed results do.
 */
export function stepLineOf(lines: readonly string[], id: string): number | undefined {
  for (let index = 0; index < lines.length; index++) {
    const line = lines[index];
    const item = /^(\s*)-\s+(?:\{\s*)?id:\s*["']?([\p{L}\p{N}_.-]+)["']?\s*(?:[,}]|#|$)/u
        .exec(line);
    if (item === null || item[2] !== id) {
      continue;
    }
    const parent = ancestorKeys(line, lines.slice(0, index), 1);
    if (parent.length === 1 && (parent[0] === 'steps' || parent[0] === 'pipeline')) {
      return index;
    }
  }
  return undefined;
}

/**
 * The step ids declared above `line` — in a job's pipeline the earlier steps, the only ones
 * a reference can name; in a route's response block every step, all of which ran.
 */
export function stepsDeclaredAbove(lines: readonly string[], line: number): string[] {
  const ids: string[] = [];
  for (let index = 0; index < line && index < lines.length; index++) {
    const item = /^(\s*)-\s+(?:\{\s*)?id:\s*["']?([\p{L}\p{N}_.-]+)["']?\s*(?:[,}]|#|$)/u
        .exec(lines[index]);
    if (item === null || ids.includes(item[2])) {
      continue;
    }
    const parent = ancestorKeys(lines[index], lines.slice(0, index), 1);
    if (parent.length === 1 && (parent[0] === 'steps' || parent[0] === 'pipeline')) {
      ids.push(item[2]);
    }
  }
  return ids;
}

/** The blocks whose entries are bindable paths, so a root completes there and nowhere else. */
const PATH_BLOCKS = new Set(['model', 'body', 'payload', 'params']);

export type PathCompletionContext =
  /** After `steps.` at a scalar position: the declared step ids. */
  | { kind: 'step' }
  /** A scalar under model:/body:/payload:/params:: the route's sources and `steps`. */
  | { kind: 'root' };

/**
 * The completion context of a cursor typing a bindable path. `steps.` completes to the
 * declared step ids wherever a scalar value is being typed (a push `file:`, an `attach:`, a
 * reader `spool:`, a body field). A bare root completes only inside the blocks whose entries
 * are paths — `model:`, `body:`, `payload:`, `params:` (block form, or a flow map on the key's
 * own line) — because every other `key: ` in a document is not asking for one.
 */
export function pathCompletionAt(lineText: string, character: number,
    linesAbove: readonly string[]): PathCompletionContext | undefined {
  const head = lineText.slice(0, character);
  const value = /(?:^|[{,])\s*(?:-\s+)?["']?([^\s:#"'{}\[\],]+)["']?:\s*["']?([\p{L}\p{N}_.-]*)$/u
      .exec(head);
  if (value === null || NOT_A_PATH_KEY.has(value[1])) {
    return undefined;
  }
  const typed = value[2];
  if (/^steps\.[\p{L}\p{N}_-]*$/u.test(typed)) {
    return { kind: 'step' };
  }
  if (typed.includes('.')) {
    return undefined;
  }
  const flowBlock = /^\s*(?:-\s+)?(\w+):\s*\{/u.exec(head);
  if (flowBlock !== null && PATH_BLOCKS.has(flowBlock[1])) {
    return { kind: 'root' };
  }
  const ancestors = ancestorKeys(lineText, linesAbove, 1);
  return ancestors.length === 1 && PATH_BLOCKS.has(ancestors[0]) ? { kind: 'root' } : undefined;
}

/**
 * Whether a block-form `source:` line is directly under an `enrich:` entry: its nearest
 * less-indented key is the enrichment's name, and that key's own parent is `enrich:`. The
 * walk reads upward past blank, comment and deeper lines, the way the `views:` sequence
 * context does; a `source:` under `params:`, `on:`, or anything else is not a reference.
 */
function isEnrichmentSource(lineText: string, linesAbove: readonly string[]): boolean {
  if (!/^\s*source:/.test(lineText)) {
    return false;
  }
  const ancestors = ancestorKeys(lineText, linesAbove, 2);
  return ancestors.length === 2 && ancestors[1] === 'enrich';
}

/**
 * The keys enclosing a line, nearest first, up to `depth` of them: each is the closest line
 * above with a smaller indent that declares a key. A `- ` item's indent is the dash's.
 */
function ancestorKeys(lineText: string, linesAbove: readonly string[], depth: number):
    string[] {
  const keys: string[] = [];
  let indent = /^\s*/.exec(lineText)![0].length;
  for (let index = linesAbove.length - 1; index >= 0 && keys.length < depth; index--) {
    const above = linesAbove[index];
    if (/^\s*(#|$)/.test(above)) {
      continue;
    }
    const aboveIndent = /^\s*/.exec(above)![0].length;
    if (aboveIndent >= indent) {
      continue;
    }
    const key = /^\s*(?:-\s+)?(["']?)([^\s:#"'{}\[\]][^:#]*?)\1:(?:\s|$)/u.exec(above);
    if (key === null) {
      return keys;
    }
    keys.push(key[2]);
    indent = aboveIndent;
  }
  return keys;
}

function stringOrNull(value: unknown): string | null {
  return typeof value === 'string' ? value : null;
}

/**
 * The explorer's route annotation — "GET /api/users · query-json" — from whichever
 * parts the manifest declares (consume/batch/mcp routes carry no method or path).
 */
export function routeDescription(route: RouteSymbol): string | undefined {
  const served = [route.method, route.path]
      .filter((part): part is string => part !== null && part !== '').join(' ');
  const parts = [served, route.recipe ?? '']
      .filter((part) => part !== '');
  return parts.length === 0 ? undefined : parts.join(' · ');
}

function toSymbol(value: unknown, nameField: string): DeclaredSymbol {
  if (typeof value !== 'object' || value === null) {
    throw new SymbolsContractError('a symbol is not an object');
  }
  const symbol = value as Record<string, unknown>;
  const name = symbol[nameField];
  const source = symbol.source;
  if (typeof name !== 'string' || typeof source !== 'string') {
    throw new SymbolsContractError(`a symbol lacks '${nameField}'/'source'`);
  }
  const line = symbol.line;
  return {
    name,
    source,
    line: typeof line === 'number' && Number.isInteger(line) && line >= 1 ? line : null,
  };
}

/**
 * A `policy:`/`message:`/`domain:`/`use:`/`decision:` value span, or a
 * `title:`/`label:` value that may be a key. A `use:` names a shared rule in a
 * `validate:` block and a shared decision in a `decide:` block; the line alone cannot
 * tell them apart, so the reference kind is `shared` and the providers search both
 * namespaces (names are unique within each, and the pools never overlap in practice).
 */
export interface SymbolReference {
  kind: 'policy' | 'message' | 'maybe-message' | 'domain' | 'shared' | 'decision'
      | 'workflow' | 'calendar' | 'job' | 'catalog';
  value: string;
  /** 0-based columns of the value span. */
  start: number;
  end: number;
}

const REFERENCE = /^(\s*(?:-\s+)?(policy|message|title|label|domain|use|decision|workflow|calendar|after|codes):\s*)(["']?)([^\s#"']+)\3/;

const KIND_BY_KEY: Record<string, SymbolReference['kind']> = {
  policy: 'policy',
  message: 'message',
  domain: 'domain',
  use: 'shared',
  // The decide: suite target (docs/decision-tables.md) names the decision directly.
  decision: 'decision',
  // The transition:/dispatch: suite targets (docs/transition-engine.md) name the
  // workflow directly.
  workflow: 'workflow',
  // A schedule's business-day calendar (docs/jobs.md): fail-open at fire time, so the
  // editor is where a typo gets caught early.
  calendar: 'calendar',
  // trigger: after: chains to a declared job (docs/jobs.md).
  after: 'job',
  // A domain's legal values may be a code catalog's codes (docs/code-catalogs.md); a
  // mistyped name resolves nothing and every value is refused at runtime.
  codes: 'catalog',
};

export function symbolReferenceAt(lineText: string, character: number): SymbolReference | undefined {
  const match = REFERENCE.exec(lineText);
  if (match === null) {
    return undefined;
  }
  const start = match[1].length + match[3].length;
  const end = start + match[4].length;
  if (character < start || character > end) {
    return undefined;
  }
  const kind = KIND_BY_KEY[match[2]] ?? 'maybe-message';
  return { kind, value: match[4], start, end };
}

/**
 * The completion context of a cursor sitting after `policy:`, `message:`, `domain:`,
 * `use:`, or `decision:` — in block form or inside a flow map, so the wave-4 input
 * shape `salary: { domain: salary, policy: hr.write }` (docs/view-composition.md)
 * completes both references.
 */
export function completionKindAt(lineText: string, character: number):
    'policy' | 'message' | 'domain' | 'shared' | 'decision' | 'workflow' | 'calendar'
    | 'job' | 'catalog' | undefined {
  const head = lineText.slice(0, character);
  const match = /(?:^\s*(?:-\s+)?|[{,]\s*)(policy|message|domain|use|decision|workflow|calendar|after|codes):\s*(["']?)[^\s#"',}]*$/.exec(head);
  return match === null
      ? undefined
      : KIND_BY_KEY[match[1]] as
          'policy' | 'message' | 'domain' | 'shared' | 'decision' | 'workflow'
          | 'calendar' | 'job' | 'catalog';
}
