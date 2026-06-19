// TesseraQL system app bootstrap (design ch. 12): Hypermedia Components behaviors plus the
// htmx wiring the kit expects. Served self-hosted; loaded as an ES module so the strict
// default-src 'self' content security policy applies unchanged.
//
// The behaviors bundle auto-installs every behavior at DOMContentLoaded; importing it for
// its side effect is the whole setup. registerCodeLanguage is the kit's pluggable-grammar hook
// for the live hc-code editor overlay (hc #264).
import { registerCodeLanguage } from "/assets/vendor/hypermedia-components__core/dist/hc.behaviors.min.js";

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
    const isWord = (ch) => /[A-Za-z0-9_]/.test(ch);
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
        } else if (/[A-Za-z_]/.test(c)) {
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
