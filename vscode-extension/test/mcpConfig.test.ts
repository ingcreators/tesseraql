import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mergeClaudeMcp, mergeVsCodeMcp } from '../src/core/mcpConfig';

test('a fresh .vscode/mcp.json registers the stdio dev-tools server', () => {
  const result = mergeVsCodeMcp(undefined, 'tesseraql');
  assert.equal(result.changed, true);
  assert.equal(result.conflict, false);
  assert.deepEqual(JSON.parse(result.content), {
    servers: {
      tesseraql: { type: 'stdio', command: 'tesseraql', args: ['mcp', '--app', '.'] },
    },
  });
});

test('existing servers are preserved', () => {
  const existing = JSON.stringify({ servers: { other: { command: 'x' } } });
  const result = mergeVsCodeMcp(existing, 'tesseraql');
  const parsed = JSON.parse(result.content);
  assert.deepEqual(Object.keys(parsed.servers).sort(), ['other', 'tesseraql']);
  assert.equal(result.conflict, false);
});

test('an identical entry is a no-op, a different one is a conflict', () => {
  const identical = mergeClaudeMcp(
      mergeClaudeMcp(undefined, 'tesseraql').content, 'tesseraql');
  assert.equal(identical.changed, false);
  assert.equal(identical.conflict, false);

  const foreign = JSON.stringify({ mcpServers: { tesseraql: { command: 'other-tool' } } });
  const conflict = mergeClaudeMcp(foreign, 'tesseraql');
  assert.equal(conflict.conflict, true);
  assert.equal(JSON.parse(conflict.content).mcpServers.tesseraql.command, 'tesseraql');
});

test('unparseable existing content is refused, never rewritten', () => {
  // mcp.json is JSONC in practice — VS Code itself writes comments into it — so this is the
  // ordinary hand-tended file, not a rare corruption. Merging used to rebuild the document from
  // an empty root, silently dropping every other server, comment, and top-level key.
  const existing = `{
  // the team's shared servers
  "servers": { "other": { "command": "x" } },
}`;
  const result = mergeVsCodeMcp(existing, 'tesseraql');

  assert.equal(result.unparseable, true);
  assert.equal(result.changed, false, 'there is nothing safe to write');
  assert.equal(result.conflict, false, 'it is not a conflicting entry — the file was never read');
  assert.equal(result.content, existing, 'the file comes back untouched');
});

test('a root that is not a JSON object is refused too', () => {
  // Valid JSON, no place to hold servers: merging into {} would replace the file just as surely.
  for (const existing of ['[1, 2]', '"a string"', '42']) {
    const result = mergeVsCodeMcp(existing, 'tesseraql');
    assert.equal(result.unparseable, true, existing);
    assert.equal(result.content, existing, existing);
  }
});

test('a well-formed file still merges', () => {
  const result = mergeVsCodeMcp(JSON.stringify({ servers: { other: { command: 'x' } } }),
      'tesseraql');
  assert.equal(result.unparseable, false);
  assert.equal(result.changed, true);
  assert.deepEqual(Object.keys(JSON.parse(result.content).servers).sort(), ['other', 'tesseraql']);
});
