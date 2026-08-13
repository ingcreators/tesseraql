package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;
import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mail channels, their templates and the expression roots those templates read.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class MailRules implements LintRule {

    private static final String INVALID_MAIL_CHANNEL = "TQL-BATCH-5304";

    private static final String UNKNOWN_MAIL_FRAGMENT = "TQL-TPL-2002";

    private static final String UNRESOLVED_MAIL_EXPRESSION = "TQL-TPL-2003";

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        lintMailChannels(context.appHome(), manifest.config(), findings);
    }

    /** {@code ~{tql/email/<library> :: <fragment>} references in a mail body. */
    private static final Pattern EMAIL_FRAGMENT_REF = Pattern
            .compile("~\\{tql/email/(hc-email(?:-layout)?)\\s*::\\s*("
                    + io.tesseraql.core.sql.SqlIdentifiers.IDENTIFIER + ")");

    /** The root identifier of a {@code ${...}} expression. */
    private static final Pattern EXPR_ROOT = Pattern
            .compile("\\$\\{\\s*(" + io.tesseraql.core.sql.SqlIdentifiers.IDENTIFIER + ")");

    /** {@code th:each="alias[, iterStat] : ..."} alias declarations. */
    private static final Pattern EACH_ALIAS = Pattern.compile(
            "th:each=\"\\s*(" + io.tesseraql.core.sql.SqlIdentifiers.IDENTIFIER
                    + ")\\s*(?:,\\s*(" + io.tesseraql.core.sql.SqlIdentifiers.IDENTIFIER
                    + "))?\\s*:");

    /** {@code th:with="a=..., b=..."} alias declarations. */
    private static final Pattern WITH_ALIAS = Pattern.compile("th:with=\"([^\"]*)\"");

    /** Expression roots that are always fine: the mail model plus literal keywords. */
    private static final Set<String> MAIL_ROOTS = Set.of("payload", "event", "true", "false",
            "null");

    /**
     * The mail wiring lints (docs/pages-and-mail-lints.md D2): a mail channel's template is
     * only exercised at delivery time, so the wiring is validated at build time instead —
     * the {@code template:} file exists inside the app home (the send-time
     * {@code TQL-BATCH-5304} surfaced early), an {@code .html} body references only
     * fragments the {@code tql/email} library actually declares ({@code TQL-TPL-2002},
     * read from the app's shadow copy when present, else the bundled library — whichever
     * resolves at render), and {@code ${...}} roots in the body and the channel's
     * {@code subject} resolve against the mail model — {@code payload}, {@code event}, or
     * an alias the template itself defines ({@code TQL-TPL-2003}, a warning: expression
     * aliasing can be arbitrarily clever). A channel whose {@code template:} value carries
     * a {@code ${...}} config placeholder is environment-dependent and skipped.
     */
    void lintMailChannels(Path appHome, AppConfig config, List<LintFinding> findings) {
        if (!(config.navigate("tesseraql.notifications.channels") instanceof Map<?, ?> map)) {
            return;
        }
        String configSource = "config/tesseraql.yml";
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> channel)
                    || !"mail".equals(channel.get("type"))) {
                continue;
            }
            String name = String.valueOf(entry.getKey());
            if (channel.get("subject") instanceof String subject) {
                lintMailExpressionRoots(name, "subject", subject, Set.of(), configSource,
                        findings);
            }
            // No default recipient: delivery fails at send unless every notification's
            // payload carries a to key — legal, but worth saying at build time.
            if (channel.get("to") == null) {
                findings.add(new LintFinding(INVALID_MAIL_CHANNEL, WARNING, configSource,
                        "Mail channel '" + name + "' declares no to: — delivery fails"
                                + " unless every notification payload carries a to key"));
            }
            if (!(channel.get("template") instanceof String template)
                    || template.contains("${")) {
                continue;
            }
            Path resolved = appHome.resolve(template).normalize();
            if (!resolved.startsWith(appHome) || !Files.isRegularFile(resolved)) {
                findings.add(new LintFinding(INVALID_MAIL_CHANNEL, ERROR, configSource,
                        "Mail channel '" + name + "': template '" + template
                                + "' is not a file inside the app home"));
                continue;
            }
            String body;
            try {
                body = Files.readString(resolved);
            } catch (java.io.IOException ex) {
                findings.add(new LintFinding(INVALID_MAIL_CHANNEL, WARNING, template,
                        "Mail channel '" + name + "': template could not be read: "
                                + ex.getMessage()));
                continue;
            }
            if (template.endsWith(".html")) {
                Matcher ref = EMAIL_FRAGMENT_REF.matcher(body);
                while (ref.find()) {
                    String library = ref.group(1);
                    String fragment = ref.group(2);
                    Set<String> declared = io.tesseraql.yaml.template.EmailFragments
                            .signatures(appHome, library).keySet();
                    if (!declared.contains(fragment)) {
                        findings.add(new LintFinding(UNKNOWN_MAIL_FRAGMENT, ERROR, template,
                                "Mail channel '" + name + "': unknown fragment '" + fragment
                                        + "' — tql/email/" + library + " declares "
                                        + declared));
                    }
                }
            }
            lintMailExpressionRoots(name, "template", body, templateAliases(body), template,
                    findings);
        }
    }

    /** The aliases a template defines itself ({@code th:each} / {@code th:with}). */
    private Set<String> templateAliases(String body) {
        Set<String> aliases = new HashSet<>();
        Matcher each = EACH_ALIAS.matcher(body);
        while (each.find()) {
            aliases.add(each.group(1));
            if (each.group(2) != null) {
                aliases.add(each.group(2));
            }
        }
        Matcher with = WITH_ALIAS.matcher(body);
        while (with.find()) {
            for (String assignment : with.group(1).split(",")) {
                int eq = assignment.indexOf('=');
                if (eq > 0) {
                    aliases.add(assignment.substring(0, eq).trim());
                }
            }
        }
        return aliases;
    }

    /** One warning per unresolvable root — the helpdesk {@code ${ticket}} bug class. */
    private void lintMailExpressionRoots(String channel, String where, String text,
            Set<String> aliases, String source, List<LintFinding> findings) {
        Set<String> reported = new HashSet<>();
        Matcher matcher = EXPR_ROOT.matcher(text);
        while (matcher.find()) {
            String root = matcher.group(1);
            if (MAIL_ROOTS.contains(root) || aliases.contains(root) || !reported.add(root)) {
                continue;
            }
            findings.add(new LintFinding(UNRESOLVED_MAIL_EXPRESSION, WARNING, source,
                    "Mail channel '" + channel + "' " + where + ": '${" + root
                            + "…}' does not resolve — the mail model carries payload and"
                            + " event"));
        }
    }
}
