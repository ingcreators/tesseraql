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

test('unparseable existing content is a conflict, not a crash', () => {
  const result = mergeVsCodeMcp('{not json', 'tesseraql');
  assert.equal(result.conflict, true);
  assert.deepEqual(Object.keys(JSON.parse(result.content)), ['servers']);
});
