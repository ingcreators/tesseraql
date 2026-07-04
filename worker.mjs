// Cloudflare Worker — docs site entrypoint (mirrors hypermedia-components/worker.mjs).
//
// The Astro build under docs-site/ uses `base: '/tesseraql'`, so every emitted URL is
// prefixed (e.g. /tesseraql/_astro/foo.css) while the build output itself is flat
// (docs-site/dist/_astro/foo.css). This Worker strips the base prefix from incoming
// requests before forwarding them to the Static Assets binding so the file resolves.
//
// Workers Static Assets `_redirects` does not support 200 (rewrite) status codes,
// which is why the prefix is handled in JS instead of declaratively.
//
// Configured via wrangler.jsonc at the repo root. `run_worker_first` is true so bare
// `/` visits are redirected to the base path before the assets binding sees them.

const BASE = '/tesseraql';

export default {
  /**
   * @param {Request} request
   * @param {{ ASSETS: { fetch: (req: Request) => Promise<Response> } }} env
   */
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === '/' || url.pathname === '' || url.pathname === BASE) {
      return Response.redirect(`${url.origin}${BASE}/`, 301);
    }

    if (!url.pathname.startsWith(`${BASE}/`)) {
      return new Response('Not Found', {
        status: 404,
        headers: { 'content-type': 'text/plain; charset=utf-8' },
      });
    }

    const stripped = new URL(url);
    stripped.pathname = url.pathname.slice(BASE.length) || '/';
    return env.ASSETS.fetch(new Request(stripped, request));
  },
};
