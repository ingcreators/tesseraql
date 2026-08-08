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

/** A mounted route as the manifest resolves it: the file plus its served identity. */
export interface RouteSymbol {
  id: string | null;
  source: string;
  method: string | null;
  path: string | null;
  recipe: string | null;
}

/** A declared batch job: its file plus the one-line trigger story. */
export interface JobSymbol {
  name: string;
  source: string;
  line: number | null;
  trigger: string | null;
}

export interface AppSymbols {
  policies: DeclaredSymbol[];
  messages: DeclaredSymbol[];
  domains: DeclaredSymbol[];
  rules: DeclaredSymbol[];
  decisions: DeclaredSymbol[];
  calendars: DeclaredSymbol[];
  routes: RouteSymbol[];
  workflows: WorkflowSymbol[];
  jobs: JobSymbol[];
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
    decisions?: unknown; calendars?: unknown; routes?: unknown; workflows?: unknown;
    jobs?: unknown;
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
    routes: optionalRoutes(document.routes),
    // Absent on a pre-0.10 CLI: workflows degrade to empty, same rule.
    workflows: optionalWorkflows(document.workflows),
    jobs: optionalJobs(document.jobs),
  };
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
    };
  });
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
      | 'workflow' | 'calendar' | 'job';
  value: string;
  /** 0-based columns of the value span. */
  start: number;
  end: number;
}

const REFERENCE = /^(\s*(?:-\s+)?(policy|message|title|label|domain|use|decision|workflow|calendar|after):\s*)(["']?)([^\s#"']+)\3/;

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
    | 'job' | undefined {
  const head = lineText.slice(0, character);
  const match = /(?:^\s*(?:-\s+)?|[{,]\s*)(policy|message|domain|use|decision|workflow|calendar|after):\s*(["']?)[^\s#"',}]*$/.exec(head);
  return match === null
      ? undefined
      : KIND_BY_KEY[match[1]] as
          'policy' | 'message' | 'domain' | 'shared' | 'decision' | 'workflow'
          | 'calendar' | 'job';
}
