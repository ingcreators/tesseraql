import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import starlightLinksValidator from 'starlight-links-validator';
import starlightLlmsTxt from 'starlight-llms-txt';
import { SECTIONS } from './nav.mjs';

// The TesseraQL documentation site (docs/docs-site.md): Astro Starlight, deployed to
// Cloudflare Workers Static Assets under ingcreators.com/tesseraql — mirroring the
// Hypermedia Components docs app (same org, same stack, same worker pattern).
export default defineConfig({
  site: 'https://ingcreators.com',
  base: '/tesseraql',
  integrations: [
    starlight({
      title: 'TesseraQL',
      // Brand mark follows the ingcreators family grammar (see brand/tesseraql-icon.svg);
      // public/favicon.svg is the ≤24px variant, picked up by Starlight's default path.
      logo: {
        src: './src/assets/tesseraql-icon.svg',
        alt: 'TesseraQL',
      },
      description:
        'A SQL-first framework for hypermedia business applications: YAML routes, 2-way SQL, and server-rendered HTML on Apache Camel.',
      plugins: [
        // Fail the build on broken internal links or anchors over the rendered
        // route graph - the site-side complement of the sync's completeness guard.
        starlightLinksValidator(),
        // /llms.txt for AI coding agents: TesseraQL apps are YAML + SQL documents,
        // so a model holding the docs can author working applications directly.
        starlightLlmsTxt({
          projectName: 'TesseraQL',
          description:
            'A SQL-first framework for hypermedia business applications: YAML routes, 2-way SQL files, server-rendered Thymeleaf/htmx HTML on Apache Camel.',
          details: [
            'Facts an agent should hold when authoring a TesseraQL application:',
            '',
            '- An application is YAML documents (`kind: route | job | view`) plus 2-way SQL files; the SQL runs as-is in any SQL client (the 2-way invariant).',
            '- HTML responses render Thymeleaf templates with Hypermedia Components and htmx; JSON is the same route with a different `Accept`.',
            '- The CLI: `tesseraql new | dev | test | migrate | admission`; Studio (browser IDE) serves at `/_tesseraql/studio` during `dev`.',
            '- Errors carry `TQL-<DOMAIN>-<n>` codes; the error reference indexes all of them with source provenance.',
            '- Framework tables are prefixed `tql_*`; app migrations live under `db/migration` per dialect.',
          ].join('\n'),
          promote: ['index*', 'getting-started*', 'five-minute-demo*'],
        }),
      ],
      social: [
        {
          icon: 'github',
          label: 'GitHub',
          href: 'https://github.com/ingcreators/tesseraql',
        },
      ],
      sidebar: SECTIONS.map(({ label, items }) => ({ label, items })),
    }),
  ],
});
