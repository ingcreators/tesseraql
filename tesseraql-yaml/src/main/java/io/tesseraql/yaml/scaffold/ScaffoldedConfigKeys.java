package io.tesseraql.yaml.scaffold;

import java.util.Map;

/**
 * The consumer registry for every configuration key the {@code tesseraql new} skeleton emits
 * (docs/config-consumers.md): key path → the repo-relative source file that reads it. The
 * scaffold⇄consumer drift test renders the actual templates, walks every leaf key, and fails
 * the build on a key missing here — "wire it or don't emit it" — then probes each registered
 * file for the key's leaf segment so the registry cannot rot into the lie it guards against.
 *
 * <p>{@code db.main.*} keys are consumed transitively: the template maps them through
 * placeholders into {@code tesseraql.datasources.main.*}, which {@code DataSources} reads —
 * they register the class at the end of that chain.
 */
public final class ScaffoldedConfigKeys {

    private ScaffoldedConfigKeys() {
    }

    /** Emitted key path → repo-relative consuming source file. */
    public static final Map<String, String> CONSUMERS = Map.ofEntries(
            Map.entry("server.port",
                    "tesseraql-runtime/src/main/java/io/tesseraql/runtime/TesseraqlRuntime.java"),
            Map.entry("db.main.url",
                    "tesseraql-runtime/src/main/java/io/tesseraql/runtime/DataSources.java"),
            Map.entry("db.main.username",
                    "tesseraql-runtime/src/main/java/io/tesseraql/runtime/DataSources.java"),
            Map.entry("db.main.password",
                    "tesseraql-runtime/src/main/java/io/tesseraql/runtime/DataSources.java"),
            Map.entry("db.main.maximumPoolSize",
                    "tesseraql-runtime/src/main/java/io/tesseraql/runtime/DataSources.java"),
            Map.entry("tesseraql.sessions.idleTimeout",
                    "tesseraql-runtime/src/main/java/io/tesseraql/runtime/TesseraqlRuntime.java"),
            Map.entry("tesseraql.app.name",
                    "tesseraql-compiler/src/main/java/io/tesseraql/compiler/RouteCompiler.java"),
            Map.entry("tesseraql.app.work",
                    "tesseraql-yaml/src/main/java/io/tesseraql/yaml/config/WorkHome.java"),
            Map.entry("tesseraql.datasources.main.jdbcUrl",
                    "tesseraql-runtime/src/main/java/io/tesseraql/runtime/DataSources.java"),
            Map.entry("tesseraql.datasources.main.username",
                    "tesseraql-runtime/src/main/java/io/tesseraql/runtime/DataSources.java"),
            Map.entry("tesseraql.datasources.main.password",
                    "tesseraql-runtime/src/main/java/io/tesseraql/runtime/DataSources.java"),
            Map.entry("tesseraql.datasources.main.maximumPoolSize",
                    "tesseraql-runtime/src/main/java/io/tesseraql/runtime/DataSources.java"),
            Map.entry("tesseraql.identity.defaultRealm",
                    "tesseraql-runtime/src/main/java/io/tesseraql/runtime/IdentityConfigFactory.java"),
            Map.entry("tesseraql.identity.realms.local.type",
                    "tesseraql-runtime/src/main/java/io/tesseraql/runtime/IdentityConfigFactory.java"),
            Map.entry("tesseraql.identity.realms.local.datasource",
                    "tesseraql-runtime/src/main/java/io/tesseraql/runtime/IdentityConfigFactory.java"),
            Map.entry("tesseraql.studio.enabled",
                    "tesseraql-studio/src/main/java/io/tesseraql/studio/StudioAppProvider.java"),
            Map.entry("tesseraql.security.defaults.routes",
                    "tesseraql-yaml/src/main/java/io/tesseraql/yaml/config/SecurityDefaults.java"),
            Map.entry("tesseraql.security.responseHeaders",
                    "tesseraql-yaml/src/main/java/io/tesseraql/yaml/config/ResponseHeaderDefaults.java"),
            Map.entry("tesseraql.security.jwt.secret",
                    "tesseraql-runtime/src/main/java/io/tesseraql/runtime/SecurityConfigFactory.java"),
            Map.entry("tesseraql.security.jwt.audience",
                    "tesseraql-runtime/src/main/java/io/tesseraql/runtime/SecurityConfigFactory.java"),
            Map.entry("tesseraql.security.jwt.rolesClaim",
                    "tesseraql-runtime/src/main/java/io/tesseraql/runtime/SecurityConfigFactory.java"),
            Map.entry("tesseraql.security.jwt.permissionsClaim",
                    "tesseraql-runtime/src/main/java/io/tesseraql/runtime/SecurityConfigFactory.java"),
            Map.entry("tesseraql.security.jwt.tenantClaim",
                    "tesseraql-runtime/src/main/java/io/tesseraql/runtime/SecurityConfigFactory.java"),
            Map.entry("tesseraql.security.policies",
                    "tesseraql-runtime/src/main/java/io/tesseraql/runtime/SecurityConfigFactory.java"));
}
