// TesseraQL system app bootstrap (design ch. 12): Hypermedia Components behaviors plus the
// htmx wiring the kit expects. Served self-hosted; loaded as an ES module so the strict
// default-src 'self' content security policy applies unchanged.
//
// The behaviors bundle auto-installs every behavior at DOMContentLoaded; importing it for
// its side effect is the whole setup. registerCodeLanguage is the kit's pluggable-grammar hook
// for the live hc-code editor overlay (hc #264).
// Relative to this module, not to the origin: an application served under a base path
// (docs/base-path.md) loads it from <base>/assets/_tesseraql/, and a module specifier
// resolves against the importing module's own URL — which already carries the prefix.
import { registerCodeLanguage } from "../vendor/hypermedia-components__core/dist/hc.behaviors.min.js";

// A 2-way SQL grammar for the live editor (Studio backlog E): it mirrors the server-side
// SqlHighlighter so the editor matches the read-only / diff surfaces — crucially classifying
// 2-way SQL block-comment directives (/*%if … */, binds) as `meta`, which a generic SQL grammar
// can't. The tokens reconstruct the source exactly (the kit declines to highlight otherwise).
const TQL_SQL_KEYWORDS = new Set([
    "select", "from", "where", "and", "or", "not", "null", "is", "in", "like", "between",
    "join", "inner", "left", "right", "full", "outer", "cross", "on", "using", "as",
    "group", "by", "order", "having", "limit", "offset", "fetch", "distinct", "union",
    "all", "exists", "case", "when", "then", "else", "end", "asc", "desc", "with",
    "insert", "into", "values", "update", "set", "delete", "returning", "count", "sum",
    "avg", "min", "max", "coalesce", "cast", "true", "false",
]);

registerCodeLanguage("tql-sql", (text) => {
    const tokens = [];
    const n = text.length;
    let i = 0;
    let plainStart = 0;
    const flushPlain = (end) => {
        if (end > plainStart) {
            tokens.push({ text: text.slice(plainStart, end) });
        }
    };
    const isWord = (ch) => /[\p{L}\p{N}_]/u.test(ch);
    while (i < n) {
        const c = text[i];
        const next = i + 1 < n ? text[i + 1] : "";
        if (c === "-" && next === "-") { // a -- remark runs to end of line
            flushPlain(i);
            let end = text.indexOf("\n", i);
            if (end < 0) {
                end = n;
            }
            tokens.push({ tok: "comment", text: text.slice(i, end) });
            i = end;
            plainStart = i;
        } else if (c === "/" && next === "*") { // a /* … */ block = a 2-way directive (meta)
            flushPlain(i);
            let end = text.indexOf("*/", i + 2);
            end = end < 0 ? n : end + 2;
            tokens.push({ tok: "meta", text: text.slice(i, end) });
            i = end;
            plainStart = i;
        } else if (c === "'") { // a '…' string literal, '' is an embedded quote
            flushPlain(i);
            let end = i + 1;
            while (end < n) {
                if (text[end] === "'") {
                    if (text[end + 1] === "'") { end += 2; continue; }
                    end++;
                    break;
                }
                end++;
            }
            tokens.push({ tok: "string", text: text.slice(i, end) });
            i = end;
            plainStart = i;
        } else if (c >= "0" && c <= "9") {
            flushPlain(i);
            let end = i;
            while (end < n && (isWord(text[end]) || text[end] === ".")) {
                end++;
            }
            tokens.push({ tok: "number", text: text.slice(i, end) });
            i = end;
            plainStart = i;
        } else if (/[\p{L}_]/u.test(c)) {
            let end = i;
            while (end < n && isWord(text[end])) {
                end++;
            }
            const word = text.slice(i, end);
            if (TQL_SQL_KEYWORDS.has(word.toLowerCase())) {
                flushPlain(i);
                tokens.push({ tok: "keyword", text: word });
                plainStart = end;
            }
            i = end; // a non-keyword identifier stays in the plain run
        } else {
            i++;
        }
    }
    flushPlain(n);
    return tokens;
});

// htmx 2 does not swap error responses by default. TesseraQL answers htmx requests with
// hc-alert field-errors fragments (ErrorResponseRenderer); swap client errors inline so
// installFieldErrors can distribute them — server errors keep htmx's default handling.
document.body.addEventListener("htmx:beforeSwap", (event) => {
    const status = event.detail.xhr.status;
    if (status >= 400 && status < 500
            && event.detail.serverResponse.includes("data-hc-field-errors")) {
        event.detail.shouldSwap = true;
        event.detail.isError = false;
    }
});

// Sidebar active-link marking (data-hc-nav-current on the shell sidebar) and share-URL copy buttons
// (data-hc-copy) are now the kit's installNavCurrent and installCopy behaviors (hc 0.1.6, #270/#272),
// auto-installed by the behaviors bundle imported above — the local stand-ins they replaced are gone.

// Confirmed plain-form submit is the KIT's contract since hc 0.1.13 (hc-briefs.md brief 4,
// shipped and adopted): installConfirm itself calls form.requestSubmit(source) for a
// confirmed plain-form submit button, with the same htmx-verb exemption the retired local
// stand-in carried. Do not reintroduce a listener here — it would double-submit.

// Save hotkey (docs/studio-ux-refresh.md slice 5): Ctrl/Cmd+S submits the page's save form —
// the one marked data-tql-hotkey-save (the Studio source editor) — instead of the browser's
// save-page dialog. Declarative and page-scoped: pages without the attribute keep the default.
document.addEventListener("keydown", (event) => {
    if ((event.ctrlKey || event.metaKey) && !event.altKey && event.key.toLowerCase() === "s") {
        const form = document.querySelector("form[data-tql-hotkey-save]");
        if (form) {
            event.preventDefault();
            form.requestSubmit();
        }
    }
});

// Conditional field visibility is the KIT's show-when behavior since hc 0.1.13
// (data-hc-show-switch / data-hc-show-when — hc-briefs.md brief 6, shipped and adopted);
// the local data-tql-switch/-show-for stand-in is retired.

// Submit-on-change (slice 6): a control marked data-tql-submit-on-change submits its form when
// flipped — the Studio flags page's hc-switch toggles post through their plain form this way.
// Declarative; without JavaScript the switch simply does not auto-submit.
document.addEventListener("change", (event) => {
    if (event.target instanceof Element
            && event.target.matches("[data-tql-submit-on-change]")
            && event.target.form) {
        event.target.form.requestSubmit();
    }
});

// Command palette glue (docs/studio-ux-refresh.md slice 7). Two small declarative pieces the
// kit deliberately leaves to the app:
// 1. A visible opener — native <dialog> has no declarative opener, and installCommand only
//    wires the ⌘K hotkey; data-tql-open-dialog="<selector>" opens the named dialog modally.
// 2. Navigation — installCommand dispatches hc:commandselect and never touches the network;
//    palette item values here are same-app URLs, so selection navigates.
document.addEventListener("click", (event) => {
    const trigger = event.target instanceof Element
        ? event.target.closest("[data-tql-open-dialog]") : null;
    if (trigger) {
        const dialog = document.querySelector(trigger.getAttribute("data-tql-open-dialog"));
        if (dialog instanceof HTMLDialogElement) {
            dialog.showModal();
        }
    }
});
document.addEventListener("hc:commandselect", (event) => {
    const value = event.detail && event.detail.value;
    if (typeof value === "string" && value.startsWith("/")) {
        window.location.assign(value);
    }
});

// Theme persistence (roadmap Phase 48): the kit's installThemeToggle (hc 0.1.9) flips
// data-theme on <html> and fires hc:themechange — client-side only, by design. The stored
// preference is the source of truth (framework toggles carry no data-persist), so every
// change is mirrored to the account app's appearance route; the renderer re-syncs the
// tesseraql_theme cookie on the next render, which carries the choice onto pre-login pages.
// The 303 target page is not needed, hence redirect: "manual". Without a session there is
// no csrf-token meta tag and nothing to persist — the flip stays visual for the page.
document.addEventListener("hc:themechange", (event) => {
    const csrf = document.querySelector('meta[name="csrf-token"]');
    if (!csrf) {
        return;
    }
    // The shell says where the account surface lives for this page (a hosted stack member's
    // is the stack's origin scope, docs/stack-shells.md structural decision 3); the
    // import.meta.url fallback covers pages rendered outside the shared shell, where the
    // module URL is the one URL that carries the application's base path.
    const accountBase = document.querySelector('meta[name="tql-account-base"]');
    fetch(accountBase
        ? accountBase.content + "/appearance"
        : new URL("../../_tesseraql/account/appearance", import.meta.url), {
        method: "POST",
        redirect: "manual",
        headers: {
            "Content-Type": "application/x-www-form-urlencoded",
            "X-CSRF-Token": csrf.content,
        },
        body: "theme=" + encodeURIComponent(event.detail.theme),
    });
});
