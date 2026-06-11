// TesseraQL system app bootstrap (design ch. 12): installs the Hypermedia Components behaviors
// used by the shared shell and pages (data-hc-confirm dialogs). Served self-hosted; loaded as an
// ES module so the strict default-src 'self' content security policy applies unchanged.
import { installShell, installConfirm }
    from "/assets/vendor/hypermedia-components__core/dist/hc.behaviors.min.js";

installShell();
installConfirm();
