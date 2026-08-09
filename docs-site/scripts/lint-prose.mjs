// The prose lint (docs/style-guide.md): measures the published corpus against the written
// limits and fails the build on the hard ones.
//
// The audit that started this found the documentation accurate and hard to read — 25.8 words
// per sentence against a 15-20 norm, 333 sentences past 40 words, an em dash every two and a
// half sentences. Style guides without a lint drift back; this one reports every page and
// refuses the two limits that matter.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { SECTIONS } from '../nav.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const docsDir = path.resolve(here, '..', '..', 'docs');

/** A sentence this long is refused outright, however well written. */
const HARD_SENTENCE_WORDS = 60;
/** Pages already under this average are the ones the guide describes. */
const TARGET_AVERAGE_WORDS = 28;

/**
 * The document's prose blocks: paragraphs and list items, each measured on its own.
 *
 * A list item rarely ends in a full stop, so splitting the whole document on sentence
 * punctuation glues a bulleted list into one enormous "sentence" — the first version of this
 * lint reported a 154-word sentence that was really six bullets. Blocks break on blank lines
 * and on each list-item marker; code fences, tables and headings are not prose at all.
 */
function blocksOf(markdown) {
  const blocks = [];
  let current = [];
  let fenced = false;
  const flush = () => {
    if (current.length > 0) blocks.push(current.join(' '));
    current = [];
  };
  for (const line of markdown.split('\n')) {
    if (line.trimStart().startsWith('```')) {
      fenced = !fenced;
      flush();
      continue;
    }
    if (fenced || line.startsWith('|') || line.startsWith('#')) continue;
    if (line.trim() === '' || /^\s*(?:[-*+]|\d+\.)\s/.test(line)) flush();
    current.push(line.trim());
  }
  flush();
  return blocks;
}

function sentencesOf(markdown) {
  return blocksOf(markdown)
    .flatMap((block) => block.split(/(?<=[.!?])\s+/))
    .map((sentence) => sentence.replace(/\s+/g, ' ').trim())
    // A bare list marker or a stub line is not a sentence to measure.
    .filter((sentence) => sentence.replace(/^[-*+]\s*/, '').split(/\s+/).length > 4);
}

const rows = [];
const violations = [];

for (const slug of SECTIONS.flatMap((section) => section.items)) {
  // Generated pages are tables of record, not prose anyone chose the shape of.
  if (slug.startsWith('reference-')) continue;
  const file = path.join(docsDir, `${slug}.md`);
  if (!fs.existsSync(file)) continue;

  const markdown = fs.readFileSync(file, 'utf8');
  const sentences = sentencesOf(markdown);
  if (sentences.length === 0) continue;

  const words = sentences.reduce((total, s) => total + s.split(/\s+/).length, 0);
  const average = words / sentences.length;
  const overLong = sentences.filter((s) => s.split(/\s+/).length > HARD_SENTENCE_WORDS);
  const emDashes = (sentences.join(' ').match(/—/g) || []).length;

  rows.push({ slug, average, sentences: sentences.length, emDashes });

  for (const sentence of overLong) {
    violations.push(
      `docs/${slug}.md: a ${sentence.split(/\s+/).length}-word sentence (limit ${HARD_SENTENCE_WORDS}): ` +
        `"${sentence.slice(0, 90)}…"`,
    );
  }
}

rows.sort((a, b) => b.average - a.average);
const corpusAverage = rows.reduce((total, r) => total + r.average, 0) / rows.length;

console.log(`lint-prose: ${rows.length} pages, ${corpusAverage.toFixed(1)} words per sentence on average`);
const worst = rows.filter((r) => r.average > TARGET_AVERAGE_WORDS);
if (worst.length > 0) {
  console.log(`  above the ${TARGET_AVERAGE_WORDS}-word target, densest first:`);
  for (const row of worst) {
    console.log(`    ${row.average.toFixed(1)}  docs/${row.slug}.md`);
  }
}

if (violations.length > 0) {
  console.error('lint-prose: sentences past the hard limit (docs/style-guide.md):');
  for (const violation of violations) console.error(`  - ${violation}`);
  process.exit(1);
}
