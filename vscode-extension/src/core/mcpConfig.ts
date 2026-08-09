// MCP registration (docs/vscode-extension.md, Phase 56 slice 4): writes the Phase 24
// dev-tools server into a client configuration, merging with existing servers. Both
// configs land in the app home, so `--app .` resolves for any client that runs the
// server with the config's directory as cwd.

export interface McpMergeResult {
  /** The full file content after the merge (2-space indent, trailing newline). */
  content: string;
  /** False when an identical tesseraql entry is already registered. */
  changed: boolean;
  /** True when a different `tesseraql` entry exists — the caller must confirm. */
  conflict: boolean;
  /**
   * True when the existing file could not be read as a JSON object, so no merge was possible.
   * `content` is then the file's untouched text: there is nothing to write, and the caller must
   * not offer to. VS Code writes `.vscode/mcp.json` with comments (JSONC), which `JSON.parse`
   * rejects — so this is the ordinary case for a hand-tended config, not a rare corruption.
   */
  unparseable: boolean;
}

/** `.vscode/mcp.json` — the VS Code MCP client format ({@code servers}). */
export function mergeVsCodeMcp(existing: string | undefined, cliPath: string): McpMergeResult {
  return merge(existing, 'servers', { type: 'stdio', command: cliPath, args: ['mcp', '--app', '.'] });
}

/** `.mcp.json` — the Claude Code project format ({@code mcpServers}). */
export function mergeClaudeMcp(existing: string | undefined, cliPath: string): McpMergeResult {
  return merge(existing, 'mcpServers', { command: cliPath, args: ['mcp', '--app', '.'] });
}

function merge(existing: string | undefined, serversKey: string, entry: object): McpMergeResult {
  let root: Record<string, unknown> = {};
  if (existing !== undefined && existing.trim() !== '') {
    let parsed: unknown;
    try {
      parsed = JSON.parse(existing);
    } catch {
      return unparseable(existing);
    }
    // A root that is not a JSON object (an array, a scalar) has nowhere to hold servers; merging
    // into `{}` would replace the file just as surely as a parse failure does.
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return unparseable(existing);
    }
    root = parsed as Record<string, unknown>;
  }
  const servers = typeof root[serversKey] === 'object' && root[serversKey] !== null
      ? root[serversKey] as Record<string, unknown>
      : {};
  const current = servers['tesseraql'];
  const identical = current !== undefined && JSON.stringify(current) === JSON.stringify(entry);
  const conflict = current !== undefined && !identical;
  root[serversKey] = { ...servers, tesseraql: entry };
  return { content: renderRoot(root), changed: !identical, conflict, unparseable: false };
}

/**
 * The refusal: the file's own text back, and nothing to write.
 *
 * <p>This used to render a document built from an empty root — every other server entry, every
 * comment, every unrelated key gone — and flag it as a `conflict`, so the user was asked to
 * confirm overwriting "a different tesseraql entry" (a claim the code could not have checked,
 * never having parsed the file) and consented to losing the whole config instead.
 */
function unparseable(existing: string): McpMergeResult {
  return { content: existing, changed: false, conflict: false, unparseable: true };
}

function renderRoot(root: Record<string, unknown>): string {
  return JSON.stringify(root, null, 2) + '\n';
}
