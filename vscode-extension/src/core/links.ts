// Reference navigation (docs/vscode-extension.md, Phase 55 slice 2): `file:`,
// `view:`, and `template:` values resolve against the document's directory exactly
// as the runtime resolves them. Matching is line-by-line on the documented key
// shapes — no YAML semantics enter the extension; a value without a file extension
// (e.g. a view document's own `view: list` kind) is not a reference.

export interface ReferenceLink {
  /** 0-based line of the value. */
  line: number;
  /** 0-based start/end columns of the linked span (the `::fragment` suffix excluded). */
  start: number;
  end: number;
  /** The document-relative target file. */
  target: string;
}

const REFERENCE = /^(\s*(?:-\s+)?(?:file|view|template):\s*)(["']?)([^\s#"']+)\2/;
const TARGET = /\.(sql|yml|html)$/;

export function referenceLinks(text: string): ReferenceLink[] {
  const links: ReferenceLink[] = [];
  const lines = text.split('\n');
  for (let line = 0; line < lines.length; line++) {
    const match = REFERENCE.exec(lines[line]);
    if (match === null) {
      continue;
    }
    const value = match[3];
    const target = value.includes('::') ? value.slice(0, value.indexOf('::')) : value;
    if (!TARGET.test(target)) {
      continue;
    }
    const start = match[1].length + match[2].length;
    links.push({ line, start, end: start + target.length, target });
  }
  return links;
}
