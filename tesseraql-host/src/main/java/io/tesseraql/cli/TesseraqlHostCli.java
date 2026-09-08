package io.tesseraql.cli;

import io.tesseraql.core.TesseraqlVersion;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * The deployment distribution's entry point (docs/runtime-footprint.md decision 1): the verbs an
 * operator runs against a production stack, and nothing else. The command implementations are the
 * developer CLI's own, unchanged; what this class changes is what gets loaded. Every command class
 * arrives with {@code tesseraql-cli}, this module's only compile dependency, and the shade filters
 * none of them out — what the pom's exclusions drop is the workshop, embedded-database and
 * artifact-resolver <em>jars</em> those dev-only verbs depend on. A root command that never names
 * them never loads them, and that is what makes dropping those jars safe.
 *
 * <p>It lives in {@code io.tesseraql.cli} so the package-private command classes are reachable;
 * the deployment runs on a plain {@code -cp lib/*} classpath, where the split package is legal.
 */
@Command(name = "tesseraql-host", mixinStandardHelpOptions = true, versionProvider = TesseraqlHostCli.VersionProvider.class, description = "TesseraQL deployment host: serve and operate an installed stack.", subcommands = {
        HostCommand.class,
        DeployCommand.class,
        RoutesCommand.class,
        TokenCommand.class,
        MigrateCommand.class,
        JobCommand.class,
        IdentitySchemaCommand.class,
        VerifyCommand.class,
        AdmissionCommand.class,
        DuckDbCommand.class
})
public final class TesseraqlHostCli implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    /** Reports the framework version from the single source ({@link TesseraqlVersion}). */
    static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{"TesseraQL " + TesseraqlVersion.current()};
        }
    }

    public static void main(String[] args) {
        // Honor HTTP_PROXY/HTTPS_PROXY/NO_PROXY (the container/CI standard the JDK ignores) before
        // any outbound work, so webhooks, exporters and connectors reach the network behind a
        // proxy. No update nudge here: a deployment's version is an operator decision, not a
        // terminal hint.
        ProxyEnvironment.bridgeFromEnvironment();
        int exitCode = commandLine().execute(args);
        System.exit(exitCode);
    }

    /**
     * The host's picocli front-end: the operator verbs plus the shared exception shaping (an
     * unreachable database is a one-line operator message, not a stack trace).
     */
    static CommandLine commandLine() {
        return new CommandLine(new TesseraqlHostCli())
                .setExecutionExceptionHandler(new UnreachableDatabaseHandler());
    }
}
