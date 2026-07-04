// Content sync (docs/docs-site.md): copies the mapped docs/*.md into Starlight's
// content directory, deriving frontmatter from each document (title = its H1,
// description = its first paragraph) and rewriting same-tree markdown links to site
// URLs. docs/ stays the canonical, GitHub-browsable tree; everything this script
// writes is gitignored (*.md here — the authored chrome is *.mdx and is left alone).
//
// This is also the completeness guard: a docs/*.md that is neither mapped in
// nav.mjs nor excluded fails the build, as does a mapped or excluded entry whose
// file no longer exists.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { BASE, SECTIONS, EXCLUDED } from '../nav.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const docsDir = path.resolve(here, '..', '..', 'docs');
const outDir = path.resolve(here, '..', 'src', 'content', 'docs');
const github = 'https://github.com/ingcreators/tesseraql';

const slugs = SECTIONS.flatMap((section) => section.items);
const mappedFiles = slugs.map((slug) => `${slug}.md`);
const actual = fs.readdirSync(docsDir).filter((name) => name.endsWith('.md'));

const problems = [];
for (const name of actual) {
  if (!mappedFiles.includes(name) && !EXCLUDED.includes(name)) {
    problems.push(
      `docs/${name} is neither mapped into a nav.mjs section nor excluded - map it or exclude it deliberately`,
    );
  }
}
for (const name of mappedFiles) {
  if (!actual.includes(name)) {
    problems.push(`nav.mjs maps docs/${name}, which does not exist`);
  }
}
for (const name of EXCLUDED) {
  if (!actual.includes(name)) {
    problems.push(`nav.mjs excludes docs/${name}, which does not exist - stale exclusion`);
  }
  if (mappedFiles.includes(name)) {
    problems.push(`docs/${name} is both mapped and excluded`);
  }
}
if (new Set(mappedFiles).size !== mappedFiles.length) {
  problems.push('nav.mjs maps a document twice');
}
if (problems.length > 0) {
  console.error('sync-content: the nav manifest and docs/ disagree:');
  for (const problem of problems) console.error(`  - ${problem}`);
  process.exit(1);
}

/**
 * Same-tree `<name>.md` links become site URLs; links to excluded docs go to GitHub;
 * `../<path>` links out of the docs tree (source files, CHANGELOG, examples) become
 * GitHub blob links — valid when browsing the repo, dead on the site otherwise.
 */
function rewriteLinks(markdown) {
  return markdown
    .replace(/\]\(([A-Za-z0-9][A-Za-z0-9._-]*)\.md(#[^)]*)?\)/g, (whole, name, fragment) => {
      if (EXCLUDED.includes(`${name}.md`)) {
        return `](${github}/blob/main/docs/${name}.md)`;
      }
      return `](${BASE}/${name.toLowerCase()}/${fragment ?? ''})`;
    })
    .replace(/\]\(\.\.\/([^)]+)\)/g, `](${github}/blob/main/$1)`);
}

/** First prose paragraph, markdown inline syntax flattened, for the meta description. */
function firstParagraph(body) {
  for (const block of body.split(/\n\s*\n/)) {
    const text = block.trim();
    if (
      text === '' ||
      text.startsWith('#') ||
      text.startsWith('```') ||
      text.startsWith('>') ||
      text.startsWith('-') ||
      text.startsWith('|') ||
      text.startsWith('<')
    ) {
      continue;
    }
    const flat = text
      .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
      .replace(/[`*_]/g, '')
      .replace(/\s+/g, ' ')
      .trim();
    return flat.length > 160 ? `${flat.slice(0, 157)}...` : flat;
  }
  return '';
}

fs.mkdirSync(outDir, { recursive: true });
for (const name of fs.readdirSync(outDir)) {
  if (name.endsWith('.md')) fs.rmSync(path.join(outDir, name));
}

for (const slug of slugs) {
  const raw = fs.readFileSync(path.join(docsDir, `${slug}.md`), 'utf8');
  const lines = raw.split('\n');
  const h1 = lines.findIndex((line) => line.startsWith('# '));
  const title = h1 >= 0 ? lines[h1].slice(2).trim() : slug;
  const body = h1 >= 0 ? lines.slice(h1 + 1).join('\n') : raw;
  const description = firstParagraph(body);
  const frontmatter = [
    '---',
    `title: ${JSON.stringify(title)}`,
    description ? `description: ${JSON.stringify(description)}` : null,
    // Edit links point at the canonical source, not the synced copy.
    `editUrl: ${JSON.stringify(`${github}/edit/main/docs/${slug}.md`)}`,
    '---',
    '',
  ]
    .filter(Boolean)
    .join('\n');
  fs.writeFileSync(path.join(outDir, `${slug}.md`), frontmatter + rewriteLinks(body));
}

console.log(`sync-content: ${slugs.length} documents synced, ${EXCLUDED.length} excluded.`);
