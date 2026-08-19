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
                    "tesseraql-camel-runtime/src/main/java/io/tesseraql/runtime/TesseraqlRuntime.java"),
            Map.entry("db.main.url",
                    "tesseraql-camel-runtime/src/main/java/io/tesseraql/runtime/DataSources.java"),
            Map.entry("db.main.username",
                    "tesseraql-camel-runtime/src/main/java/io/tesseraql/runtime/DataSources.java"),
            Map.entry("db.main.password",
                    "tesseraql-camel-runtime/src/main/java/io/tesseraql/runtime/DataSources.java"),
            Map.entry("db.main.maximumPoolSize",
                    "tesseraql-camel-runtime/src/main/java/io/tesseraql/runtime/DataSources.java"),
            Map.entry("tesseraql.sessions.idleTimeout",
                    "tesseraql-camel-runtime/src/main/java/io/tesseraql/runtime/TesseraqlRuntime.java"),
            Map.entry("tesseraql.app.name",
                    "tesseraql-compiler/src/main/java/io/tesseraql/compiler/RouteCompiler.java"),
            Map.entry("tesseraql.app.work",
                    "tesseraql-yaml/src/main/java/io/tesseraql/yaml/config/WorkHome.java"),
            Map.entry("tesseraql.datasources.main.jdbcUrl",
                    "tesseraql-camel-runtime/src/main/java/io/tesseraql/runtime/DataSources.java"),
            Map.entry("tesseraql.datasources.main.username",
                    "tesseraql-camel-runtime/src/main/java/io/tesseraql/runtime/DataSources.java"),
            Map.entry("tesseraql.datasources.main.password",
                    "tesseraql-camel-runtime/src/main/java/io/tesseraql/runtime/DataSources.java"),
            Map.entry("tesseraql.datasources.main.maximumPoolSize",
                    "tesseraql-camel-runtime/src/main/java/io/tesseraql/runtime/DataSources.java"),
            Map.entry("tesseraql.identity.defaultRealm",
                    "tesseraql-camel-runtime/src/main/java/io/tesseraql/runtime/IdentityConfigFactory.java"),
            Map.entry("tesseraql.identity.realms.local.type",
                    "tesseraql-camel-runtime/src/main/java/io/tesseraql/runtime/IdentityConfigFactory.java"),
            Map.entry("tesseraql.identity.realms.local.datasource",
                    "tesseraql-camel-runtime/src/main/java/io/tesseraql/runtime/IdentityConfigFactory.java"),
            Map.entry("tesseraql.studio.enabled",
                    "tesseraql-studio/src/main/java/io/tesseraql/studio/StudioAppProvider.java"),
            Map.entry("tesseraql.security.defaults.routes",
                    "tesseraql-yaml/src/main/java/io/tesseraql/yaml/config/SecurityDefaults.java"),
            Map.entry("tesseraql.security.responseHeaders",
                    "tesseraql-yaml/src/main/java/io/tesseraql/yaml/config/ResponseHeaderDefaults.java"),
            Map.entry("tesseraql.security.jwt.secret",
                    "tesseraql-camel-runtime/src/main/java/io/tesseraql/runtime/SecurityConfigFactory.java"),
            Map.entry("tesseraql.security.jwt.audience",
                    "tesseraql-camel-runtime/src/main/java/io/tesseraql/runtime/SecurityConfigFactory.java"),
            Map.entry("tesseraql.security.jwt.rolesClaim",
                    "tesseraql-camel-runtime/src/main/java/io/tesseraql/runtime/SecurityConfigFactory.java"),
            Map.entry("tesseraql.security.jwt.permissionsClaim",
                    "tesseraql-camel-runtime/src/main/java/io/tesseraql/runtime/SecurityConfigFactory.java"),
            Map.entry("tesseraql.security.jwt.tenantClaim",
                    "tesseraql-camel-runtime/src/main/java/io/tesseraql/runtime/SecurityConfigFactory.java"),
            Map.entry("tesseraql.security.policies",
                    "tesseraql-camel-runtime/src/main/java/io/tesseraql/runtime/SecurityConfigFactory.java"));
}
