// TesseraQL system app bootstrap (design ch. 12): installs the Hypermedia Components behaviors
// used by the shared shell. Served self-hosted; loaded as an ES module so the strict
// default-src 'self' content security policy applies unchanged.
import { installShell } from "/assets/vendor/hypermedia-components__core/dist/hc.behaviors.min.js";

installShell();
