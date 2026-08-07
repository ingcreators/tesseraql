// The Studio visual page builder (docs/page-builder.md D1): editor-kit's native
// architecture over a hand-owned page template. The template text never renders
// server-side inside this page (it travels in hidden textareas); the editable region is
// parsed into a same-origin srcdoc iframe with the kit stylesheets, where th:* attributes
// are inert strings and the markup is its own preview. Export is plain concatenation —
// verbatim prefix + serialize()d region + verbatim suffix — into the save form's hidden
// content field, so the source editor's /save, /apply and /render flows are reused
// unchanged (the mail composer recipe).
import {
  createEditor,
  createDragController,
  insertNode,
  moveNode,
  removeNode,
  setAttribute,
  setText,
  Overlay,
} from '/assets/vendor/hypermedia-components__editor-kit/src/index.js';

const host = document.getElementById('builder-canvas-host');
if (host) init();

async function init() {
  const content = document.getElementById('builder-content');
  const prefix = document.getElementById('builder-prefix').value;
  const suffix = document.getElementById('builder-suffix').value;
  const seed = document.getElementById('builder-region-seed');
  const frame = document.getElementById('builder-frame');
  const palette = document.getElementById('builder-palette');
  const inspector = document.getElementById('builder-inspector');
  const inspectorTitle = document.getElementById('builder-inspector-title');
  const inspectorFields = document.getElementById('builder-inspector-fields');
  const removeButton = document.getElementById('builder-remove');

  // The core manifest drives the inspector's attribute enums (docs/page-builder.md);
  // without it the builder still works, just without per-component attribute knowledge.
  const manifest = await fetch(
    '/assets/vendor/hypermedia-components__core/dist/manifest.json',
  ).then((r) => (r.ok ? r.json() : null)).catch(() => null);
  const blocks = new Map((manifest?.components ?? []).map((c) => [c.block, c]));

  // The canvas iframe: same-origin srcdoc, the parent page's stylesheets for kit
  // fidelity, no scripts (the parent drives everything via contentDocument).
  const links = [...document.querySelectorAll('link[rel="stylesheet"]')]
    .map((l) => `<link rel="stylesheet" href="${l.getAttribute('href')}">`)
    .join('');
  const theme = document.documentElement.getAttribute('data-theme') ?? 'light';
  const loaded = new Promise((resolve) => frame.addEventListener('load', resolve, { once: true }));
  frame.srcdoc = `<!doctype html><html data-theme="${theme}"><head>${links}`
    + '<style>body{margin:0;padding:1rem;min-height:100%}</style></head><body></body></html>';
  await loaded;

  const doc = frame.contentDocument;
  const mount = doc.createElement('div');
  const regionClass = host.dataset.regionClass || '';
  if (regionClass) mount.className = regionClass;
  // The canvas seeds from the server-filled inert <template> element — the region was
  // parsed once by the browser's own HTML parser, its content never executes or loads,
  // and this module performs no string-to-HTML conversion at all. The canvas frame is
  // additionally sandboxed without allow-scripts.
  for (const node of [...seed.content.childNodes]) {
    mount.appendChild(doc.importNode(node, true));
  }
  doc.body.appendChild(mount);

  // Structural containers accept drops and inserts; the mark is editor scaffolding the
  // serializers strip. Datagrid internals stay off-limits (table-aware moves deferred).
  const CONTAINERS = ['hc-stack', 'hc-cluster', 'hc-card__body', 'hc-card__footer'];
  const markContainers = () => {
    mount.setAttribute('data-hc-editor-container', '');
    for (const el of mount.querySelectorAll(CONTAINERS.map((c) => '.' + c).join(','))) {
      if (!el.closest('table')) el.setAttribute('data-hc-editor-container', '');
    }
  };
  markContainers();

  const editor = createEditor({ root: mount, manifest });

  const overlayLayer = document.createElement('div');
  overlayLayer.className = 'tql-builder-overlay';
  host.appendChild(overlayLayer);
  const overlay = new Overlay({ mount: overlayLayer, frame });
  const refreshOverlay = () => overlay.showSelection(editor.selection.items);

  const exportTemplate = () => prefix + editor.serialize() + suffix;
  const sync = () => {
    markContainers();
    content.value = exportTemplate();
    content.dispatchEvent(new Event('change', { bubbles: true }));
  };

  const blockOf = (el) => [...el.classList].map((c) => blocks.get(c)).find(Boolean) ?? null;

  // Selection: the nearest manifest block above the click, else the clicked element
  // itself — never the mount. Links and buttons in the canvas must not navigate.
  const pick = (target) => {
    let el = target instanceof Element ? target : null;
    while (el && el !== mount) {
      if (blockOf(el)) return el;
      el = el.parentElement;
    }
    el = target instanceof Element ? target : null;
    return el && el !== mount && mount.contains(el) ? el : null;
  };
  doc.addEventListener('click', (event) => {
    event.preventDefault();
    const el = pick(event.target);
    if (el) editor.selection.select(el);
    else editor.selection.clear();
  });

  // Double-click edits a leaf's text in place. The browser edits the live text node, so
  // the DOM is restored before the change goes through the stack as ONE setText — undo
  // stays coherent and the contenteditable attribute never reaches serialize().
  doc.addEventListener('dblclick', (event) => {
    // The deepest element under the pointer, not the block ancestor pick() prefers —
    // the text being edited lives on the leaf.
    const el = event.target instanceof Element ? event.target : null;
    if (!el || el === mount || !mount.contains(el) || el.children.length > 0) return;
    event.preventDefault();
    editor.selection.select(el);
    const original = el.textContent;
    el.setAttribute('contenteditable', 'plaintext-only');
    if (el.contentEditable !== 'plaintext-only') el.setAttribute('contenteditable', '');
    el.focus();
    const finish = (commit) => {
      el.removeEventListener('blur', onBlur);
      el.removeEventListener('keydown', onKey);
      el.removeAttribute('contenteditable');
      const next = el.textContent;
      el.textContent = original;
      if (commit && next !== original) editor.stack.apply(setText(el, next));
      buildInspector();
    };
    const onBlur = () => finish(true);
    const onKey = (e) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        finish(false);
      } else if (e.key === 'Enter') {
        e.preventDefault();
        el.blur();
      }
    };
    el.addEventListener('blur', onBlur);
    el.addEventListener('keydown', onKey);
  });

  const buildInspector = () => {
    const el = editor.selection.primary;
    inspectorFields.replaceChildren();
    inspector.hidden = !el;
    removeButton.disabled = !el;
    if (!el) return;
    const block = blockOf(el);
    inspectorTitle.textContent = block ? block.block : `<${el.tagName.toLowerCase()}>`;

    // Manifest-known data attributes as enums (empty = unset).
    for (const name of block?.dataAttributes ?? []) {
      const values = block.attributeValues?.[name] ?? [];
      if (values.length === 0) continue;
      const label = document.createElement('label');
      label.className = 'hc-field';
      const caption = document.createElement('span');
      caption.className = 'hc-field__label';
      caption.textContent = name;
      const select = document.createElement('select');
      select.className = 'hc-select';
      select.append(new Option('(unset)', ''));
      for (const value of values) select.append(new Option(value, value));
      select.value = el.getAttribute(name) ?? '';
      select.addEventListener('change', () => {
        editor.stack.apply(setAttribute(el, name, select.value === '' ? null : select.value));
      });
      label.append(caption, select);
      inspectorFields.appendChild(label);
    }

    // Leaf text content (setText, coalesced typing = one undo step).
    if (el.children.length === 0 && el.textContent !== '') {
      const label = document.createElement('label');
      label.className = 'hc-field';
      const caption = document.createElement('span');
      caption.className = 'hc-field__label';
      caption.textContent = 'text';
      const input = document.createElement('input');
      input.className = 'hc-input';
      input.type = 'text';
      input.spellcheck = false;
      input.value = el.textContent;
      input.addEventListener('input', () => {
        editor.stack.apply(setText(el, input.value), { coalesce: true });
      });
      label.append(caption, input);
      inspectorFields.appendChild(label);
    }

    // Every other attribute — class and th:* expressions included — edited verbatim.
    for (const attr of [...el.attributes]) {
      if (attr.name.startsWith('data-hc-editor')
          || (block?.dataAttributes ?? []).includes(attr.name)) {
        continue;
      }
      const label = document.createElement('label');
      label.className = 'hc-field';
      const caption = document.createElement('span');
      caption.className = 'hc-field__label';
      caption.textContent = attr.name;
      const input = document.createElement('input');
      input.className = 'hc-input';
      input.type = 'text';
      input.spellcheck = false;
      input.value = attr.value;
      input.addEventListener('input', () => {
        editor.stack.apply(setAttribute(el, attr.name, input.value), { coalesce: true });
      });
      label.append(caption, input);
      inspectorFields.appendChild(label);
    }
  };

  editor.selection.addEventListener('change', () => {
    buildInspector();
    refreshOverlay();
  });
  editor.stack.addEventListener('change', () => {
    sync();
    refreshOverlay();
  });
  doc.addEventListener('scroll', refreshOverlay, true);
  window.addEventListener('resize', refreshOverlay);

  // Palette: curated starter set (docs/page-builder.md — the full sweep and hc recipes
  // are palette v2). Click inserts into the selected container, else the page end.
  const SNIPPETS = [
    ['Card', '<section class="hc-card"><div class="hc-card__header">Title</div>'
      + '<div class="hc-card__body hc-stack"><p>Body</p></div></section>'],
    ['Stack', '<div class="hc-stack"><p>Stacked content</p></div>'],
    ['Cluster', '<div class="hc-cluster"><span class="hc-badge">One</span>'
      + '<span class="hc-badge">Two</span></div>'],
    ['Heading', '<h3>Heading</h3>'],
    ['Text', '<p>Text</p>'],
    ['Button', '<button type="button" class="hc-button" data-variant="primary">Action</button>'],
    ['Badge', '<span class="hc-badge">Badge</span>'],
    ['Alert', '<p class="hc-alert" data-variant="info" role="status">Message</p>'],
    ['Field', '<div class="hc-field"><label class="hc-field__label">Label</label>'
      + '<input class="hc-input" type="text"></div>'],
    ['Select', '<div class="hc-field"><label class="hc-field__label">Label</label>'
      + '<select class="hc-select"><option>Option</option></select></div>'],
    ['Empty state', '<div class="hc-empty"><p class="hc-empty__title">Nothing here yet.</p></div>'],
    ['Separator', '<hr>'],
  ];
  const instantiate = (html) => {
    const tpl = doc.createElement('template');
    tpl.innerHTML = html;
    return tpl.content.firstElementChild;
  };
  const insertTarget = () => {
    const el = editor.selection.primary;
    const container = el?.closest('[data-hc-editor-container]') ?? mount;
    return mount.contains(container) ? container : mount;
  };
  for (const [label, html] of SNIPPETS) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'hc-button';
    button.setAttribute('data-variant', 'ghost');
    button.setAttribute('data-size', 'sm');
    button.textContent = label;
    button.addEventListener('click', () => {
      const el = instantiate(html);
      const container = insertTarget();
      editor.stack.apply(insertNode(container, el, container.childNodes.length));
      editor.selection.select(el);
    });
    palette.appendChild(button);
  }

  // Drag to reorder inside the canvas (palette drags cannot cross the iframe boundary —
  // clicks insert instead). Every drop goes through the stack, so it is undoable.
  const dnd = createDragController({
    root: mount,
    onPreview: (target) => overlay.showDropIndicator(target),
    onDrop: ({ container, index, payload }) => {
      editor.stack.apply(moveNode(payload.node, container, index));
    },
    onCancel: () => overlay.showDropIndicator(null),
  });
  doc.addEventListener('pointerdown', (event) => {
    const el = pick(event.target);
    if (el) dnd.startMove(el, event);
  });

  removeButton.addEventListener('click', () => {
    const el = editor.selection.primary;
    if (el && el !== mount) editor.stack.apply(removeNode(el));
  });
  document.getElementById('builder-undo').addEventListener('click', () => editor.stack.undo());
  document.getElementById('builder-redo').addEventListener('click', () => editor.stack.redo());

  // Alt+Arrow moves the selected element among its element siblings — the keyboard path
  // to what the drag does. Indices are childNodes positions measured with the node
  // absent, exactly what moveNode consumes.
  const childNodesIndexBefore = (parent, ref, exclude) => {
    let index = 0;
    for (const node of parent.childNodes) {
      if (node === ref) break;
      if (node !== exclude) index++;
    }
    return index;
  };
  document.addEventListener('keydown', (event) => {
    const el = editor.selection.primary;
    if (!el || !event.altKey || (event.key !== 'ArrowUp' && event.key !== 'ArrowDown')) return;
    const parent = el.parentNode;
    const siblings = [...parent.children];
    const i = siblings.indexOf(el);
    let ref = null;
    if (event.key === 'ArrowUp' && i > 0) ref = siblings[i - 1];
    else if (event.key === 'ArrowDown' && i < siblings.length - 1) ref = siblings[i + 1].nextElementSibling;
    else if (event.key === 'ArrowDown') return;
    else return;
    editor.stack.apply(moveNode(el, parent, childNodesIndexBefore(parent, ref, el)));
    event.preventDefault();
  });
}
