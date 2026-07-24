package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.ComponentPolicy;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.JobFile;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.apache.camel.CamelContext;
import org.apache.camel.Component;
import org.apache.camel.support.LifecycleStrategySupport;

/**
 * Enforces the {@link ComponentPolicy} on the runtime's CamelContext
 * (docs/component-guard.md): every component registration — resolved from the classpath,
 * added by the framework, or added by a plugin — passes through the context's lifecycle
 * strategy, and a refused component fails app boot with {@code TQL-SEC-4138} naming the
 * component and the reason. Registration-time enforcement (not a post-start scan) catches
 * lazily resolved components too, including one a plugin registers mid-boot.
 */
final class ComponentGuard {

    private static final TqlErrorCode REFUSED = new TqlErrorCode(TqlDomain.SEC, 4138);

    private ComponentGuard() {
    }

    /**
     * Installs the guard; call immediately after creating the context. A poll-triggered job's
     * declared {@code source:} (sftp/ftp/...) is the app's structured component intent, so an
     * {@code allowed:} narrowing never has to restate it — the deny sets still win, so a job
     * cannot resurrect a baseline-denied component either.
     */
    static void install(CamelContext context, AppManifest manifest) {
        install(context, ComponentPolicy.from(manifest.config()), declaredPollSources(manifest));
    }

    /** The enforcement seam, also exercised directly by the unit tests. */
    static void install(CamelContext context, ComponentPolicy policy,
            Set<String> declaredSources) {
        context.addLifecycleStrategy(new LifecycleStrategySupport() {
            @Override
            public void onComponentAdd(String name, Component component) {
                if (declaredSources.contains(name.toLowerCase(Locale.ROOT))
                        && !ComponentPolicy.BASELINE_DENIED.contains(
                                name.toLowerCase(Locale.ROOT))
                        && !policy.denied().contains(name.toLowerCase(Locale.ROOT))) {
                    return;
                }
                policy.refusal(name).ifPresent(reason -> {
                    throw new TqlException(REFUSED, "Camel component '" + name + "' is "
                            + reason);
                });
            }
        });
    }

    /** The components the app's poll-triggered jobs declare as their {@code source:}. */
    private static Set<String> declaredPollSources(AppManifest manifest) {
        Set<String> sources = new LinkedHashSet<>();
        for (JobFile job : manifest.jobs()) {
            if (job.definition().trigger() != null && job.definition().trigger().poll() != null
                    && job.definition().trigger().poll().source() != null) {
                sources.add(job.definition().trigger().poll().source().trim()
                        .toLowerCase(Locale.ROOT));
            }
        }
        return sources;
    }
}
