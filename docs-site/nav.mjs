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
    label: 'Start here',
    items: ['overview', 'five-minute-demo', 'getting-started', 'your-first-app', 'faq'],
  },
  {
    // One page per application shape, routing through pages that already exist rather
    // than explaining features again: the reader who finished the tutorial had forty
    // topic pages and no order to read them in (docs/documentation-ia.md).
    label: 'Guides by use case',
    items: [
      'guide-approval-workflow',
      'guide-integration',
      'guide-analytics',
      'guide-existing-database',
    ],
  },
  {
    label: 'Concepts',
    items: ['concepts', 'app-layout', 'two-way-sql', 'identifiers', 'glossary'],
  },
  {
    label: 'Building applications',
    items: [
      'scaffolding',
      'transactional-writes',
      'declarative-validation',
      'declarative-views',
      'code-catalogs',
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
      'governance',
      'extending',
      'documentation-portal',
    ],
  },
  {
    // The surfaces every application ships with. They were referenced from thirty
    // pages and documented on none; a reader who met "the ops console's jobs page"
    // had nowhere to go (docs/documentation-ia.md).
    label: 'Consoles and tools',
    items: [
      'studio',
      'ops-console',
      'iam-admin',
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
    ],
  },
  {
    label: 'Running in production',
    items: [
      'deployment',
      'hosting',
      'promotion',
      'upgrading',
      'proxy',
    ],
  },
  {
    // Written for someone deciding whether to adopt or install, not for someone
    // building: the framework's own posture and the bar a shared app must clear.
    // security-hardening.md opens by calling itself a maintainer document, and sat
    // among the pages a reader browses to secure their own app
    // (docs/documentation-ia.md).
    label: 'Evaluating TesseraQL',
    items: ['admission', 'security-hardening', 'threat-model'],
  },
  {
    // Generated pages, committed under docs/ by tesseraql-docs-reference and
    // drift-guarded in the Maven build (docs/docs-site.md).
    label: 'Reference',
    items: [
      'reference-yaml-surface',
      'reference-cli',
      'reference-config',
      'reference-error-codes',
      'troubleshooting',
    ],
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
  // Console UX refresh (ops/IAM/account/auth review + slice plan), 2026-08-07.
  'console-ux-refresh.md',
  // HTML email (bundled hc-email fragments + Studio mail composer), 2026-08-07.
  'html-email.md',
  // Visual page builder (editor-kit canvas over ejected templates + eject ramp), 2026-08-07.
  'page-builder.md',
  // Pages overview + mail wiring lints (post-campaign gap closing), 2026-08-07.
  'pages-and-mail-lints.md',
  // Pre-1.0 contract bug fixes (wave A of the contract-consistency sweep), 2026-08-08.
  'contract-bugfixes.md',
  // Vocabulary cleanup (wave C: YAML/HTTP renames before the v1 freeze), 2026-08-08.
  'vocabulary-cleanup.md',
  // Unicode identifiers (verbatim table/column names end-to-end), 2026-08-08.
  'unicode-identifiers.md',
  // View composition (registry, fragment negotiation, embedding, field presentation/masking), 2026-08-08.
  'view-composition.md',
  // Silent tolerance sweep (fail-open/unknown-key/observability/tooling defects), 2026-08-09.
  'silent-tolerance.md',
  // Documentation information architecture (audit + section rebuild), 2026-08-09.
  'documentation-ia.md',
  // The house style the documentation is written to: a contributor convention, not a
  // page an application author needs.
  'style-guide.md',
  // Application isolation model (which multi-app mechanism serves what), 2026-08-10.
  'app-isolation-model.md',
  // Serving an application under a base path (prefix hosting, reverse proxies), 2026-08-10.
  'base-path.md',
  // The export pipeline (codec model, workbook modes, streaming, split documents), 2026-08-10.
  'export-pipeline.md',
  // Lookups and enrichment (keyed row enrichment + code catalogs), 2026-08-11.
  'lookups.md',
  // The unified source model (sources:/main, binding http arm, enrich placement), 2026-08-12.
  'unified-sources.md',
];
