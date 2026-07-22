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
    items: ['getting-started', 'five-minute-demo', 'your-first-app'],
  },
  {
    label: 'Building applications',
    items: [
      'app-layout',
      'two-way-sql',
      'scaffolding',
      'transactional-writes',
      'declarative-validation',
      'declarative-views',
      'realtime',
      'pagination',
      'response-shaping',
      'hypermedia-ui',
      'internationalization',
      'file-transfers',
      'printable-documents',
      'attachments',
      'multi-datasource',
      'duckdb',
      'testing',
      'documentation-portal',
      'vscode-extension',
    ],
  },
  {
    label: 'Platform services',
    items: [
      'jobs',
      'notifications',
      'messaging',
      'connectors',
      'account',
      'inbox',
      'productivity',
      'approval-workflow',
      'ai-mcp',
      'app-mcp',
      'copilot',
    ],
  },
  {
    label: 'Security & identity',
    items: [
      'authentication',
      'saml',
      'data-scoping',
      'multi-tenancy',
      'credential-lifecycle',
      'delegation',
      'admission',
      'security-hardening',
      'threat-model',
    ],
  },
  {
    label: 'Operations',
    items: [
      'deployment',
      'promotion',
      'upgrading',
      'proxy',
    ],
  },
  {
    // Generated pages, committed under docs/ by tesseraql-docs-reference and
    // drift-guarded in the Maven build (docs/docs-site.md).
    label: 'Reference',
    items: ['reference-yaml-surface', 'reference-error-codes'],
  },
];

/**
 * Documents deliberately not on the site: internal working trackers and project
 * planning, not user documentation. Links pointing at these rewrite to the GitHub
 * blob instead of a site URL. A stale entry (file deleted) fails the sync, keeping
 * the list honest.
 */
export const EXCLUDED = [
  'studio-backlog.md',
  'hc-briefs.md',
  'roadmap.md',
  'docs-site.md',
  // Framework-maintainer pages: releasing/building the framework itself and the
  // monorepo dev environment; their few consumer-relevant facts moved to
  // getting-started (GitHub Packages auth, driver licensing) and testing.md.
  'release.md',
  'build.md',
  'development-environment.md',
  'app-developer-distribution.md',
];
