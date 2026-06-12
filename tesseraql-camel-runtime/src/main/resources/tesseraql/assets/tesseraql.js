// TesseraQL system app bootstrap (design ch. 12): Hypermedia Components behaviors plus the
// htmx wiring the kit expects. Served self-hosted; loaded as an ES module so the strict
// default-src 'self' content security policy applies unchanged.
//
// The behaviors bundle auto-installs every behavior at DOMContentLoaded; importing it for
// its side effect is the whole setup.
import "/assets/vendor/hypermedia-components__core/dist/hc.behaviors.min.js";

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
