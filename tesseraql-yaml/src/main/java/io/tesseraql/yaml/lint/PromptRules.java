package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;
import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.PromptFile;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Application-declared MCP prompts.
 *
 * <p>A prompt is a route (docs/prompt-as-recipe.md decision 1), so it is checked against the
 * route model like a tool — plus the two things only a prompt answers for: which
 * {@code InputField} keys its pipeline can act on, and that it stays a read.
 */
final class PromptRules implements LintRule {

    private static final String PROMPT_INPUT_KEY_UNSUPPORTED = "TQL-MCP-1015";

    private static final String PROMPT_NOT_READ_ONLY = "TQL-MCP-1016";

    /**
     * The {@code InputField} keys a prompt argument may not declare, each with why it cannot act
     * — "wire it or don't declare it", applied to the authoring surface.
     *
     * <p>The rest of {@code InputField} is live on a prompt and stays accepted: {@code type},
     * {@code required}, {@code requiredWhen}, {@code default}, {@code min}/{@code max},
     * {@code minLength}/{@code maxLength}, {@code pattern}, {@code enum}, {@code format},
     * {@code items} and {@code codes} are what the binder coerces and validates the argument
     * with; {@code description} is the wire field {@code prompts/list} advertises;
     * {@code classification}/{@code mask} keep the argument out of the route audit trail, which
     * a prompt now has like every other route; and {@code domain:} supplies any of those.
     */
    private static final Map<String, String> UNSUPPORTED_KEYS = unsupportedKeys();

    private static Map<String, String> unsupportedKeys() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("policy", "policy: gates a principal supplying a field the request would"
                + " otherwise write, so on a prompt it can only refuse the argument the caller"
                + " has to send (and with no security.auth: there is no principal, so it refuses"
                + " every caller) — gate the prompt itself with security.policy:");
        keys.put("writable", "writable: says whether the request may supply the field, and a"
                + " prompt argument has no other source — writable: false is an argument no"
                + " prompts/get call can pass");
        keys.put("widget", "widget: is the form control the field renders as, and a prompt"
                + " renders a message rather than a form");
        return Map.copyOf(keys);
    }

    @Override
    public void lint(LintContext context, AppManifest manifest, List<LintFinding> findings) {
        Path appHome = context.appHome();
        for (PromptFile prompt : manifest.prompts()) {
            lintPrompt(context, appHome, manifest.config(), prompt, findings);
        }
    }

    /**
     * Lints an application-declared MCP prompt: its keys are the route model's, its arguments
     * declare only what a prompt's pipeline acts on, and it reads rather than writes — the
     * refusal the compiler already makes at startup, said at build time, before compilation,
     * the way every other recipe's shape rules are.
     */
    private void lintPrompt(LintContext context, Path appHome,
            io.tesseraql.yaml.config.AppConfig manifestConfig, PromptFile prompt,
            List<LintFinding> findings) {
        RouteDefinition definition = prompt.definition();
        String source = appHome.relativize(prompt.source()).toString().replace('\\', '/');
        // mcp documents reuse the route record plus loader-read keys; without this a typo'd
        // securty: on a prompt was dropped in silence while every other surface flagged it.
        UnknownKeyRules.lintUnknownKeys(context, appHome, prompt.source(), RouteDefinition.class,
                Set.of("description"), findings);

        for (Map.Entry<String, Set<String>> entry : declaredInputKeys(context, prompt).entrySet()) {
            for (String key : entry.getValue()) {
                String why = UNSUPPORTED_KEYS.get(key);
                if (why != null) {
                    findings.add(new LintFinding(PROMPT_INPUT_KEY_UNSUPPORTED, ERROR, source,
                            "Prompt '" + definition.id() + "' argument '" + entry.getKey()
                                    + "' declares " + key + ": which a prompt cannot act on — "
                                    + why));
                }
            }
        }

        if (!definition.steps().isEmpty()) {
            findings.add(new LintFinding(PROMPT_NOT_READ_ONLY, ERROR, source,
                    "Prompt '" + definition.id() + "' declares steps: — prompts/get is a read, so"
                            + " a prompt renders text from sources: and a prompt that writes is a"
                            + " tool"));
        }
        definition.sources().forEach((name, binding) -> {
            if (binding != null && "update".equals(binding.effectiveMode())) {
                findings.add(new LintFinding(PROMPT_NOT_READ_ONLY, ERROR, source,
                        "Prompt '" + definition.id() + "' source '" + name
                                + "' runs in update mode — prompts/get is a read, and a prompt"
                                + " that writes is a tool"));
            }
        });

        // The checks every other mcp kind already makes on the data it reads. A prompt could
        // not read data at all until this campaign, so it never needed them; now a typo'd
        // filename or an undefined policy on a prompt would reach startup while the same typo
        // on a tool is a build error.
        definition.sources().forEach((name, binding) -> {
            if (binding == null || binding.isContract() || binding.file() == null) {
                return;
            }
            if (!Files.isRegularFile(prompt.source().getParent().resolve(binding.file()))) {
                findings.add(new LintFinding(LintCodes.MISSING_SQL_FILE, ERROR, source,
                        "Source '" + name + "' references a missing SQL file: "
                                + binding.file()));
            }
        });
        String policy = definition.security() == null ? null : definition.security().policy();
        if (policy != null && !policy.isBlank()
                && !DocumentRules.policyDefined(manifestConfig, policy)) {
            findings.add(new LintFinding(LintCodes.UNDEFINED_POLICY, WARNING, source,
                    "MCP prompt references undefined policy '" + policy
                            + "' (deny by default)"));
        }
        DocumentRules.lintDatasource(context, manifestConfig, prompt.source(), definition, source,
                findings);
    }

    /**
     * The keys each argument declares <em>in this document</em>, read from the raw tree rather
     * than from the loaded field.
     *
     * <p>A field reaches most of {@code InputField} through {@code domain:} too, and the loader
     * has already merged the domain's keys into what the manifest carries — so the loaded field
     * cannot tell "the author wrote widget: here" from "the SKU domain says an SKU renders as a
     * code input". Only the first is this rule's business: refusing the second would make a
     * shared domain unusable from a prompt over a key that is merely inert there, which is a
     * tax on reuse rather than a defect caught. ({@code policy:} and {@code writable:} are
     * operational keys a domain may not declare at all, so they can only ever be written
     * here.)
     */
    private static Map<String, Set<String>> declaredInputKeys(LintContext context,
            PromptFile prompt) {
        Map<String, Object> tree = context.tree(prompt.source());
        if (tree == null || !(tree.get("input") instanceof Map<?, ?> input)) {
            return Map.of();
        }
        Map<String, Set<String>> declared = new LinkedHashMap<>();
        input.forEach((name, field) -> {
            if (field instanceof Map<?, ?> keys) {
                Set<String> spelled = new java.util.LinkedHashSet<>();
                keys.keySet().forEach(key -> spelled.add(String.valueOf(key)));
                declared.put(String.valueOf(name), spelled);
            }
        });
        return declared;
    }
}
