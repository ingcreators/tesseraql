// The chart bootstrap (docs/analytics-experience.md track 2): dashboard pages emit this
// module — beside the self-hosted Observable Plot UMD bundle — only when a chart panel
// renders, so pages without charts ship no charting code. installChart is deliberately
// outside the kit's auto-init behaviors bundle because Plot is its optional peer; without
// Plot it is a no-op and the source tables stay visible (the no-JavaScript rendering).
// It listens for htmx:load itself, so refreshOn: live dashboards re-render with no wiring.
import { installChart } from "/assets/vendor/hypermedia-components__core/dist/chart.js";

const install = () => installChart(document, { plot: window.Plot });
if (window.Plot) {
  install();
} else {
  // Both scripts are deferred; DOMContentLoaded guarantees the UMD bundle has defined
  // window.Plot even when this module happens to execute first.
  window.addEventListener("DOMContentLoaded", install, { once: true });
}
