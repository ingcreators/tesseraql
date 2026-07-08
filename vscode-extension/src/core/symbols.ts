// The symbols contract (docs/vscode-extension.md, Phase 56 slice 5): what the
// framework declares — policies, default-locale message keys, routes — with source
// lines, as `tesseraql symbols --format json` prints it. The providers navigate and
// complete over it; unknown references stay lint findings.

export interface DeclaredSymbol {
  name: string;
  source: string;
  line: number | null;
}

export interface AppSymbols {
  policies: DeclaredSymbol[];
  messages: DeclaredSymbol[];
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
  const document = parsed as { policies: unknown[]; messages: unknown[] };
  return {
    policies: document.policies.map((value) => toSymbol(value, 'name')),
    messages: document.messages.map((value) => toSymbol(value, 'key')),
  };
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

/** A `policy:`/`message:` value span, or a `title:`/`label:` value that may be a key. */
export interface SymbolReference {
  kind: 'policy' | 'message' | 'maybe-message';
  value: string;
  /** 0-based columns of the value span. */
  start: number;
  end: number;
}

const REFERENCE = /^(\s*(?:-\s+)?(policy|message|title|label):\s*)(["']?)([^\s#"']+)\3/;

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
  const key = match[2];
  const kind = key === 'policy' ? 'policy' : key === 'message' ? 'message' : 'maybe-message';
  return { kind, value: match[4], start, end };
}

/** The completion context of a cursor sitting after `policy:` or `message:`. */
export function completionKindAt(lineText: string, character: number): 'policy' | 'message' | undefined {
  const head = lineText.slice(0, character);
  const match = /^\s*(?:-\s+)?(policy|message):\s*(["']?)[^\s#"']*$/.exec(head);
  return match === null ? undefined : match[1] as 'policy' | 'message';
}
