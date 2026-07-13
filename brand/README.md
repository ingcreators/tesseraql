# TesseraQL Brand Assets

TesseraQL's marks in the **ingcreators family** design system. The
family-wide standard (palette, container, color grammar, typography,
size rules) is canonical in
[ingcreators/annot → `brand/`](https://github.com/ingcreators/annot/tree/main/brand);
this directory holds only the TesseraQL-specific assets that obey it.

## The mark

The letter **T** as a mosaic joint: a TesseraQL application is small
declarative tiles (routes, views, queries — *tesserae*), and the
framework is the one continuous piece that holds every tile together.

- **Green** `#7ef0c5` (left) — the data you already have
- **Purple** `#b391ff` (right) — the application ahead
- **Blue** `#7c9cff` — TesseraQL, the joint that bridges them; the
  only element that crosses a grout line

## Files

| File | Use case |
|---|---|
| `tesseraql-icon.svg` | Master mark (25 px and above). Full geometry notes inside. |
| `tesseraql-icon-16.svg` | Favicon-optimized variant (≤24 px). Stem pill replaced by a tile. |
| `tesseraql-wordmark.svg` | Icon + "TesseraQL" horizontal, light backgrounds |
| `tesseraql-wordmark-inverse.svg` | Horizontal, dark backgrounds |
| `tesseraql-wordmark-stacked.svg` | Icon + name + "by ingcreators", light |
| `tesseraql-wordmark-stacked-inverse.svg` | Stacked lockup, dark |
| `preview/` | Rendered PNG sheets for design review |
| `_archive/` | Rejected explorations — reference only, do not ship |

## Deployed copies (keep in sync when the mark changes)

- `vscode-extension/images/icon.png` — 256×256 render of the master
  (Marketplace listing icon; re-render, bump the extension version,
  republish).
- `docs-site/src/assets/tesseraql-icon.svg` — site logo. **Comment-free
  copy**: Astro's image metadata parser fails on SVGs that don't start
  with `<svg>`.
- `docs-site/public/favicon.svg` — comment-free copy of the 16px variant.

## Wordmark rendering note

Wordmarks reference **Sora** with the family fallback stack. For
environments where webfonts can't be guaranteed (embedded images,
PDFs), convert text to paths before distributing — see the family
README's typography section.
