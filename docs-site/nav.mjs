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
      'analytics',
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
  // Design documents that precede implementation; published once the feature ships.
  'field-domains.md',
  'route-defaults.md',
  'ambient-params.md',
  'component-guard.md',
  'config-consumers.md',
  'validation-rule-sets.md',
  // Contract-deviation sweep, 2026-07-25.
  'route-governance-parity.md',
  'poll-connector-hardening.md',
  'shared-definitions-reach.md',
  'framework-surface-parity.md',
  'yaml-surface-consumers.md',
  // Ops console write actions, 2026-07-26.
  'ops-console-actions.md',
  // Studio schema lifecycle, 2026-07-26.
  'studio-schema-lifecycle.md',
  // Session administration + poll source status, 2026-07-26.
  'session-administration.md',
  'poll-source-status.md',
  // Poll source metrics exposition, 2026-07-26.
  'poll-source-metrics.md',
  // Ops console coverage (audit page, health panel, params form), 2026-07-26.
  'ops-console-coverage.md',
  // Session rotation in place, 2026-07-26.
  'session-rotation.md',
  // Session visibility (metadata, per-session revoke, idle timeout, admin page), 2026-07-27.
  'session-visibility.md',
  // Credential throttle (login/reset rate limiting), 2026-07-27.
  'credential-throttle.md',
  // Framework datasource (transactional-coupling buckets), 2026-07-27.
  'framework-datasource.md',
  // Decision tables (value-producing shared decisions), 2026-07-30.
  'decision-tables.md',
  // Procurement demo application (suite-scale gallery design), 2026-07-31.
  'procurement-demo.md',
  // Workflow expressiveness (SQL guards, stamps, dispatch), 2026-07-31.
  'workflow-expressiveness.md',
  // Transition engine (one pipeline invoked everywhere), 2026-07-31.
  'transition-engine.md',
  // Batch platform (business date, calendars, chunking, CLI contract), 2026-08-01.
  'batch-platform.md',
  // Analytics experience (browse named datasources, kit charts, export step), 2026-08-01.
  'analytics-experience.md',
  // Studio UX refresh (full-surface review + slice plan), 2026-08-06.
  'studio-ux-refresh.md',
];
