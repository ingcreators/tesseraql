// The navigation manifest (docs/docs-site.md): the single source both the Starlight
// sidebar (astro.config.mjs) and the content sync (scripts/sync-content.mjs) read.
// Every docs/*.md is either mapped into a section here or excluded explicitly; the
// sync fails the build when a new document is neither — a doc can never silently
// miss the site.

/** The site's base path on ingcreators.com (mirrors hypermedia-components). */
export const BASE = '/tesseraql';

/** Sidebar sections in display order; items are docs/<slug>.md file slugs. */
export const SECTIONS = [
  {
    label: 'Tutorial',
    items: ['getting-started', 'five-minute-demo'],
  },
  {
    label: 'Building applications',
    items: [
      'app-layout',
      'transactional-writes',
      'multi-datasource',
      'declarative-validation',
      'declarative-views',
      'pagination',
      'response-shaping',
      'hypermedia-ui',
      'internationalization',
      'printable-documents',
      'attachments',
      'scaffolding',
      'documentation-portal',
    ],
  },
  {
    label: 'Platform services',
    items: [
      'account',
      'inbox',
      'productivity',
      'approval-workflow',
      'notifications',
      'messaging',
      'connectors',
      'ai-mcp',
      'copilot',
    ],
  },
  {
    label: 'Security & identity',
    items: [
      'authentication',
      'data-scoping',
      'credential-lifecycle',
      'delegation',
      'admission',
    ],
  },
  {
    label: 'Operations',
    items: [
      'deployment',
      'promotion',
      'release',
      'build',
      'development-environment',
      'proxy',
      'app-developer-distribution',
    ],
  },
  {
    // Generated pages, committed under docs/ by tesseraql-docs-reference and
    // drift-guarded in the Maven build (docs/docs-site.md).
    label: 'Reference',
    items: ['reference-yaml-surface', 'reference-error-codes'],
  },
  {
    label: 'Project & design',
    items: ['roadmap', 'docs-site'],
  },
];

/**
 * Documents deliberately not on the site: internal working trackers, not user
 * documentation. Links pointing at these rewrite to the GitHub blob instead of a
 * site URL. A stale entry (file deleted) fails the sync, keeping the list honest.
 */
export const EXCLUDED = ['studio-backlog.md', 'hc-briefs.md'];
