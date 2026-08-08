// The Studio mail composer (docs/html-email.md D4): an editor-kit canvas over the
// tql/email/* block grammar. Each canvas child holds one fragment invocation as data
// attributes (data-tql-fragment / data-tql-args) — never literal th:replace, which the
// composer page's own render would evaluate — and the exporter writes the template text
// into the save form's hidden content field, so the source editor's draft flow
// (/save, /apply, /render) is reused unchanged.
import {
  createEditor,
  createDragController,
  insertNode,
  moveNode,
  removeNode,
  setAttribute,
  Overlay,
} from '/assets/vendor/hypermedia-components__editor-kit/src/index.js';

const canvas = document.getElementById('mail-canvas');
if (canvas) {
  const content = document.getElementById('composer-content');
  const title = document.getElementById('composer-title');
  const preheader = document.getElementById('composer-preheader');
  const inspector = document.getElementById('mail-inspector');
  const inspectorFields = document.getElementById('mail-inspector-fields');
  const removeButton = document.getElementById('composer-remove');

  // fragment -> parameter names, read off the palette buttons the server rendered.
  const params = {};
  for (const button of document.querySelectorAll('#mail-palette [data-tql-fragment]')) {
    params[button.dataset.tqlFragment] = JSON.parse(button.dataset.tqlParams);
  }

  const editor = createEditor({ root: canvas });

  const blocks = () => [...canvas.querySelectorAll('[data-tql-fragment]')];
  const argsOf = (block) => JSON.parse(block.dataset.tqlArgs || '[]');

  const summary = (args) => {
    if (args.length === 0) return '';
    const first = args[0].replace(/^['|]|['|]$/g, '');
    return first.length > 60 ? first.slice(0, 57) + '…' : first;
  };

  // The exporter — the JS half of the round-trip whose Java half is
  // MailComposer.parse; the shapes must stay in lockstep.
  const exportTemplate = () => {
    const lines = [];
    lines.push(`<div th:replace="~{tql/email/hc-email-layout :: hcLayout(${title.value},`);
    lines.push(`    ${preheader.value}, ~{:: content})}">`);
    lines.push('  <div th:fragment="content">');
    for (const block of blocks()) {
      const args = argsOf(block);
      const invocation = args.length === 0
        ? block.dataset.tqlFragment
        : `${block.dataset.tqlFragment}(${args.join(', ')})`;
      lines.push(`    <div th:replace="~{tql/email/hc-email :: ${invocation}}"></div>`);
    }
    lines.push('  </div>');
    lines.push('</div>');
    return lines.join('\n') + '\n';
  };

  const sync = () => {
    for (const block of blocks()) {
      block.querySelector('.tql-mail-block__chip').textContent = summary(argsOf(block));
    }
    content.value = exportTemplate();
    // htmx listens for this on the hidden field (the preview's hx-trigger).
    content.dispatchEvent(new Event('change', { bubbles: true }));
  };

  // Editor chrome: the overlay layer lives beside the canvas, never inside it.
  const host = canvas.parentElement;
  const overlayMount = document.createElement('div');
  overlayMount.className = 'tql-mail-overlay';
  overlayMount.setAttribute('data-hc-editor-only', '');
  host.appendChild(overlayMount);
  const overlay = new Overlay({ mount: overlayMount });
  const refreshOverlay = () => overlay.showSelection(editor.selection.items);

  const buildInspector = () => {
    const block = editor.selection.primary;
    inspectorFields.replaceChildren();
    inspector.hidden = !block;
    removeButton.disabled = !block;
    if (!block) return;
    const fragment = block.dataset.tqlFragment;
    const names = params[fragment] ?? [];
    const args = argsOf(block);
    names.forEach((name, i) => {
      const label = document.createElement('label');
      label.className = 'hc-field';
      const caption = document.createElement('span');
      caption.className = 'hc-field__label';
      caption.textContent = `${fragment} · ${name}`;
      const input = document.createElement('input');
      input.className = 'hc-input';
      input.type = 'text';
      input.spellcheck = false;
      input.value = args[i] ?? '';
      input.addEventListener('input', () => {
        const next = argsOf(block);
        next[i] = input.value;
        // Coalesced: a typing burst is one undo step.
        editor.stack.apply(
          setAttribute(block, 'data-tql-args', JSON.stringify(next)),
          { coalesce: true },
        );
      });
      label.append(caption, input);
      inspectorFields.appendChild(label);
    });
    if (names.length === 0) {
      const note = document.createElement('p');
      note.className = 'hc-field__message';
      note.textContent = `${fragment} takes no arguments.`;
      inspectorFields.appendChild(note);
    }
  };

  const makeBlock = (fragment, args) => {
    const block = document.createElement('div');
    block.className = 'tql-mail-block';
    block.tabIndex = 0;
    block.dataset.tqlFragment = fragment;
    block.dataset.tqlArgs = JSON.stringify(args);
    const badge = document.createElement('span');
    badge.className = 'hc-badge';
    badge.setAttribute('data-variant', 'info');
    badge.textContent = fragment;
    const chip = document.createElement('span');
    chip.className = 'tql-mail-block__chip';
    chip.textContent = summary(args);
    block.append(badge, chip);
    return block;
  };

  // Selection: click (or focus) a block; the drag controller's threshold keeps
  // clicks below it reaching here.
  canvas.addEventListener('click', (event) => {
    const block = event.target.closest('[data-tql-fragment]');
    if (block) editor.selection.select(block);
  });
  editor.selection.addEventListener('change', () => {
    buildInspector();
    refreshOverlay();
  });
  editor.stack.addEventListener('change', () => {
    sync();
    refreshOverlay();
  });
  window.addEventListener('resize', refreshOverlay);
  document.addEventListener('scroll', refreshOverlay, true);

  // Palette: click appends (and selects) a fresh block; drag inserts at the pointer.
  for (const button of document.querySelectorAll('#mail-palette [data-tql-fragment]')) {
    button.addEventListener('click', () => {
      const block = makeBlock(button.dataset.tqlFragment, JSON.parse(button.dataset.tqlArgs));
      // {before: null} appends — element-based insertion points (editor-kit 0.1.0)
      // make whitespace text nodes irrelevant to positioning.
      editor.stack.apply(insertNode(canvas, block, { before: null }));
      editor.selection.select(block);
    });
  }

  // Drag to reorder, committed through the stack so every drop is undoable.
  const dnd = createDragController({
    root: canvas,
    onPreview: (target) => overlay.showDropIndicator(target),
    onDrop: ({ container, index, payload }) => {
      editor.stack.apply(moveNode(payload.node, container, index));
    },
    onCancel: () => overlay.showDropIndicator(null),
  });
  canvas.addEventListener('pointerdown', (event) => {
    const block = event.target.closest('[data-tql-fragment]');
    if (block) dnd.startMove(block, event);
  });

  removeButton.addEventListener('click', () => {
    const block = editor.selection.primary;
    if (block) editor.stack.apply(removeNode(block));
  });

  document.getElementById('composer-undo').addEventListener('click', () => editor.stack.undo());
  document.getElementById('composer-redo').addEventListener('click', () => editor.stack.redo());

  // Alt+Arrow moves the selected block — the keyboard path to what the drag does,
  // via element-based insertion points (editor-kit 0.1.0).
  canvas.addEventListener('keydown', (event) => {
    const block = event.target.closest('[data-tql-fragment]');
    if (!block || !event.altKey) return;
    const siblings = blocks();
    const i = siblings.indexOf(block);
    if (event.key === 'ArrowUp' && i > 0) {
      editor.stack.apply(moveNode(block, canvas, { before: siblings[i - 1] }));
    } else if (event.key === 'ArrowDown' && i < siblings.length - 1) {
      editor.stack.apply(moveNode(block, canvas, { before: siblings[i + 1].nextElementSibling }));
    } else {
      return;
    }
    event.preventDefault();
    block.focus();
  });

  title.addEventListener('input', sync);
  preheader.addEventListener('input', sync);
}
