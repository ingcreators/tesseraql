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
    try {
      const parsed = JSON.parse(existing);
      if (typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)) {
        root = parsed as Record<string, unknown>;
      }
    } catch {
      // Unparseable config: treat as a conflict so the caller confirms the rewrite.
      return { content: render(root, serversKey, entry), changed: true, conflict: true };
    }
  }
  const servers = typeof root[serversKey] === 'object' && root[serversKey] !== null
      ? root[serversKey] as Record<string, unknown>
      : {};
  const current = servers['tesseraql'];
  const identical = current !== undefined && JSON.stringify(current) === JSON.stringify(entry);
  const conflict = current !== undefined && !identical;
  root[serversKey] = { ...servers, tesseraql: entry };
  return { content: renderRoot(root), changed: !identical, conflict };
}

function render(root: Record<string, unknown>, serversKey: string, entry: object): string {
  return renderRoot({ ...root, [serversKey]: { tesseraql: entry } });
}

function renderRoot(root: Record<string, unknown>): string {
  return JSON.stringify(root, null, 2) + '\n';
}
