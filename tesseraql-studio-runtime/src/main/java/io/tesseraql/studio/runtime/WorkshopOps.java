package io.tesseraql.studio.runtime;

import static java.util.Map.entry;

import java.util.Map;

/**
 * The workshop's export table (docs/studio-shell.md structural decision 2): every provider a
 * member's workshop API answers for, with the verb it answers on. This table is the only door —
 * there is no generic invoke-any-provider dispatch, so a provider registered tomorrow is
 * unreachable through the shell until it is enumerated here. Generated from the studio app's
 * route documents (each row is a provider some route calls) and checked against the
 * registrations by the module's tests.
 */
final class WorkshopOps {

    /** Provider name → the HTTP verb the workshop API exposes it on. */
    static final Map<String, String> OPS = Map.ofEntries(
            entry("docs.coverage", "GET"),
            entry("docs.decisions", "GET"),
            entry("docs.domains", "GET"),
            entry("docs.export", "GET"),
            entry("docs.htmx", "GET"),
            entry("docs.index", "GET"),
            entry("docs.openapi", "GET"),
            entry("docs.releaseDiff", "GET"),
            entry("docs.route", "GET"),
            entry("docs.routesPdf", "GET"),
            entry("docs.rules", "GET"),
            entry("docs.schema", "GET"),
            entry("docs.search", "GET"),
            entry("docs.share", "GET"),
            entry("docs.shareCoverage", "GET"),
            entry("docs.shareTable", "GET"),
            entry("docs.table", "GET"),
            entry("studio.apply", "POST"),
            entry("studio.audit", "GET"),
            entry("studio.baselineCapture", "POST"),
            entry("studio.calendars.save", "POST"),
            entry("studio.calendars.toggle", "POST"),
            entry("studio.calendars.view", "GET"),
            entry("studio.command", "GET"),
            entry("studio.config", "GET"),
            entry("studio.configSet", "POST"),
            entry("studio.connectors.credential", "POST"),
            entry("studio.connectors.egress", "POST"),
            entry("studio.connectors.view", "GET"),
            entry("studio.connectors.webhook", "POST"),
            entry("studio.copilot.reset", "POST"),
            entry("studio.copilot.view", "GET"),
            entry("studio.data", "GET"),
            entry("studio.data.editForm", "GET"),
            entry("studio.data.export", "GET"),
            entry("studio.data.update", "POST"),
            entry("studio.decisionBuilder", "GET"),
            entry("studio.decisionBuilder.build", "POST"),
            entry("studio.decisions.save", "POST"),
            entry("studio.decisions.view", "GET"),
            entry("studio.discard", "POST"),
            entry("studio.drafts", "GET"),
            entry("studio.draftsApplyAll", "POST"),
            entry("studio.draftsDiscardAll", "POST"),
            entry("studio.ejectView", "POST"),
            entry("studio.explorer", "GET"),
            entry("studio.flags", "GET"),
            entry("studio.flagsRemove", "POST"),
            entry("studio.flagsSet", "POST"),
            entry("studio.health", "GET"),
            entry("studio.jobs.save", "POST"),
            entry("studio.jobs.view", "GET"),
            entry("studio.mail", "GET"),
            entry("studio.mailComposer", "GET"),
            entry("studio.mailTestSend", "POST"),
            entry("studio.menu.add", "POST"),
            entry("studio.menu.move", "POST"),
            entry("studio.menu.preview", "GET"),
            entry("studio.menu.remove", "POST"),
            entry("studio.menu.view", "GET"),
            entry("studio.messageSet", "POST"),
            entry("studio.messages", "GET"),
            entry("studio.migration.build", "POST"),
            entry("studio.migration.columns", "GET"),
            entry("studio.migration.create", "POST"),
            entry("studio.migration.diff", "POST"),
            entry("studio.migration.dryRun", "POST"),
            entry("studio.migration.migrate", "POST"),
            entry("studio.migration.new", "GET"),
            entry("studio.newForm", "GET"),
            entry("studio.newRoute", "POST"),
            entry("studio.pageBuilder", "GET"),
            entry("studio.pages", "GET"),
            entry("studio.policyAddRule", "POST"),
            entry("studio.policyRemoveRule", "POST"),
            entry("studio.preview", "POST"),
            entry("studio.render", "POST"),
            entry("studio.routeForm.save", "POST"),
            entry("studio.routeForm.view", "GET"),
            entry("studio.runTests", "POST"),
            entry("studio.save", "POST"),
            entry("studio.scaffold.apply", "POST"),
            entry("studio.scaffold.preview", "POST"),
            entry("studio.scaffold.tables", "GET"),
            entry("studio.schemaRefresh", "POST"),
            entry("studio.security", "GET"),
            entry("studio.source", "GET"),
            entry("studio.sqlBuilder.build", "POST"),
            entry("studio.sqlBuilder.columns", "GET"),
            entry("studio.sqlBuilder.new", "GET"),
            entry("studio.tryRecord", "POST"),
            entry("studio.tryRun", "POST"),
            entry("studio.tryit", "GET"),
            entry("studio.validationBuilder", "GET"),
            entry("studio.validationBuilder.build", "POST"),
            entry("studio.wizard.identity.apply", "POST"),
            entry("studio.wizard.oidc.apply", "POST"),
            entry("studio.wizard.preview", "POST"),
            entry("studio.wizard.saml.apply", "POST"),
            entry("studio.wizard.scim.apply", "POST"));

    /**
     * The token-authorized share pages (F8 slice 3): public by design — the signed, expiring
     * token in the query is the authorization, verified by the provider itself — so these rows
     * bypass the atom gate on both sides of the delegation, exactly as the pages bypass the
     * browser session.
     */
    static final java.util.Set<String> PUBLIC = java.util.Set.of(
            "docs.share", "docs.shareTable", "docs.shareCoverage");

    /** File-response ops: their models template downloads, so the shell adds no chrome. */
    static final java.util.Set<String> NO_CHROME = java.util.Set.of(
            "studio.data.export", "docs.htmx", "docs.openapi", "docs.routesPdf");

    private WorkshopOps() {
    }
}
