package io.tesseraql.scim.routes;

import io.tesseraql.compiler.ext.ExtensionContext;
import io.tesseraql.compiler.ext.RuntimeExtension;
import io.tesseraql.core.outbox.OutboxEventSink;
import io.tesseraql.core.sql.ContractStatement;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.scim.JdbcScimResourceMapping;
import io.tesseraql.scim.ScimContract;
import io.tesseraql.scim.ScimGroupContract;
import io.tesseraql.scim.ScimGroupOutboundSink;
import io.tesseraql.scim.ScimGroupProvisioner;
import io.tesseraql.scim.ScimGroupService;
import io.tesseraql.scim.ScimOutboundClient;
import io.tesseraql.scim.ScimOutboundSink;
import io.tesseraql.scim.ScimProvisioner;
import io.tesseraql.scim.ScimTarget;
import io.tesseraql.scim.ScimUserService;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Installs SCIM provisioning when the tesseraql-scim jar is on the classpath (design ch. 10.15,
 * 47): the inbound {@code /scim/v2} routes when {@code tesseraql.scim.enabled} is true, and the
 * outbound user/group provisioning sink (bound as {@code tesseraqlOutboxEventSink}) when
 * {@code tesseraql.scim.outbound.enabled} is true.
 */
public final class ScimRuntimeExtension implements RuntimeExtension {

    @Override
    public String name() {
        return "scim";
    }

    @Override
    public boolean enabled(AppConfig config) {
        return flag(config, "tesseraql.scim.enabled")
                || flag(config, "tesseraql.scim.outbound.enabled");
    }

    @Override
    public void install(ExtensionContext context) throws Exception {
        AppManifest manifest = context.manifest();
        if (flag(manifest.config(), "tesseraql.scim.enabled")) {
            io.tesseraql.core.telemetry.Tracer tracer = context.bean(
                    TesseraqlProperties.TRACER_BEAN, io.tesseraql.core.telemetry.Tracer.class);
            new ScimRoutes(
                    buildUserService(manifest, context.dataSource(), tracer),
                    buildGroupService(manifest, context.dataSource(), tracer),
                    buildAttributeCapture(context)).install(context.runtime());
        }
        if (flag(manifest.config(), "tesseraql.scim.outbound.enabled")) {
            context.bind(TesseraqlProperties.OUTBOX_EVENT_SINK_BEAN,
                    outboundSink(manifest, context.dataSource()));
        }
    }

    /**
     * The outbound sink provisioning {@code USER_*}/{@code GROUP_*} events to a downstream provider.
     * User and group provisioners share one HTTP client and one resource-mapping table (group keys
     * are namespaced), so both resource types are provisioned from the same outbox. At-least-once
     * retry is preserved because a sink failure propagates.
     */
    private static OutboxEventSink outboundSink(AppManifest manifest,
            javax.sql.DataSource dataSource) {
        ScimTarget target = new ScimTarget(
                manifest.config().requireString("tesseraql.scim.outbound.target.url"),
                manifest.config().getString("tesseraql.scim.outbound.target.token").orElse(""));
        JdbcScimResourceMapping mapping = new JdbcScimResourceMapping(dataSource);
        mapping.ensureSchema();
        ScimOutboundClient client = new ScimOutboundClient(target);
        ScimOutboundSink userSink = new ScimOutboundSink(new ScimProvisioner(client, mapping));
        ScimGroupOutboundSink groupSink = new ScimGroupOutboundSink(
                new ScimGroupProvisioner(client, mapping));
        return event -> {
            userSink.send(event);
            groupSink.send(event);
        };
    }

    /**
     * The bound a SCIM contract statement runs under: the same {@code tesseraql.sql.timeoutSeconds}
     * a route's statement runs under, because a provisioning call has no claim to run longer than a
     * page (docs/contract-sql-execution.md structural decision 3). Package-private so the test
     * that pins the key name can read it without booting a runtime.
     */
    static int sqlTimeoutSeconds(AppConfig config) {
        return config.getString("tesseraql.sql.timeoutSeconds")
                .map(Integer::parseInt)
                .orElse(ContractStatement.DEFAULT_TIMEOUT_SECONDS);
    }

    /** Builds the SCIM user service from the configured contract SQL files (design ch. 10.15). */
    private static ScimUserService buildUserService(AppManifest manifest,
            javax.sql.DataSource dataSource, io.tesseraql.core.telemetry.Tracer tracer) {
        ScimContract contract = new ScimContract(
                readSql(manifest, "tesseraql.scim.users.create"),
                readSql(manifest, "tesseraql.scim.users.findById"),
                readSql(manifest, "tesseraql.scim.users.list"),
                readSql(manifest, "tesseraql.scim.users.replace"),
                readSql(manifest, "tesseraql.scim.users.delete"),
                readSql(manifest, "tesseraql.scim.users.findByUserName"),
                readSqlOptional(manifest, "tesseraql.scim.users.count"),
                readKeys(manifest.config().navigate("tesseraql.scim.users.keys")));
        return new ScimUserService(dataSource, contract)
                .dialect(dialect(manifest.config()))
                .sqlTimeoutSeconds(sqlTimeoutSeconds(manifest.config()))
                .tracer(tracer);
    }

    /** The per-operation SQL keys a deployment declares under {@code tesseraql.scim.groups.}. */
    private static final java.util.List<String> GROUP_OPS = java.util.List.of("create",
            "findById", "list", "replace", "delete", "listMembers", "addMember", "removeMember");

    /**
     * The declared group operations that are missing: empty means the deployment's own SQL,
     * all of them means the bundled managed set, anything else is a boot refusal — two schemas
     * mixed one statement at a time looks like a bug in the framework rather than in the
     * configuration (docs/contract-sql-execution.md structural decision 6).
     */
    static java.util.List<String> missingGroupOps(AppConfig config) {
        return GROUP_OPS.stream()
                .filter(op -> config.getString("tesseraql.scim.groups." + op).isEmpty())
                .toList();
    }

    /**
     * True when no group operation is declared (the bundled set applies); a partial
     * declaration is refused here, at boot, naming the missing keys.
     */
    static boolean useBundledGroupSet(AppConfig config) {
        java.util.List<String> missing = missingGroupOps(config);
        if (missing.size() == GROUP_OPS.size()) {
            return true;
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("tesseraql.scim.groups is partially configured —"
                    + " missing " + missing.stream()
                            .map(op -> "tesseraql.scim.groups." + op)
                            .collect(java.util.stream.Collectors.joining(", "))
                    + ". Declare all of them for your own schema, or none of them to use the"
                    + " bundled managed group set (docs/contract-sql-execution.md).");
        }
        return false;
    }

    /**
     * Builds the SCIM group service — from the deployment's contract SQL when all operations
     * are declared, from the bundled managed set when none are — or {@code null} when group
     * provisioning is disabled, leaving the {@code /Groups} endpoints unmounted.
     */
    private static ScimGroupService buildGroupService(AppManifest manifest,
            javax.sql.DataSource dataSource, io.tesseraql.core.telemetry.Tracer tracer) {
        if (!flag(manifest.config(), "tesseraql.scim.groups.enabled")) {
            return null;
        }
        if (useBundledGroupSet(manifest.config())) {
            // None declared: the bundled managed set against the managed identity schema,
            // ids minted as grp-<uuid> because tql_groups.group_id is a supplied column.
            return new ScimGroupService(dataSource,
                    io.tesseraql.scim.ScimGroupPack.contract(dialect(manifest.config())))
                    .idSupplier(io.tesseraql.scim.ScimGroupPack.idSupplier())
                    .dialect(dialect(manifest.config()))
                    .sqlTimeoutSeconds(sqlTimeoutSeconds(manifest.config()))
                    .tracer(tracer);
        }
        ScimGroupContract contract = new ScimGroupContract(
                readSql(manifest, "tesseraql.scim.groups.create"),
                readSql(manifest, "tesseraql.scim.groups.findById"),
                readSql(manifest, "tesseraql.scim.groups.list"),
                readSql(manifest, "tesseraql.scim.groups.replace"),
                readSql(manifest, "tesseraql.scim.groups.delete"),
                readSql(manifest, "tesseraql.scim.groups.listMembers"),
                readSql(manifest, "tesseraql.scim.groups.addMember"),
                readSql(manifest, "tesseraql.scim.groups.removeMember"),
                readSqlOptional(manifest, "tesseraql.scim.groups.count"),
                readKeys(manifest.config().navigate("tesseraql.scim.groups.keys")));
        return new ScimGroupService(dataSource, contract)
                .dialect(dialect(manifest.config()))
                .sqlTimeoutSeconds(sqlTimeoutSeconds(manifest.config()))
                .tracer(tracer);
    }

    /**
     * The columns the store assigns on create (docs/contract-sql-execution.md structural
     * decision 2), declared as a list or a comma-separated string — the same concept a command
     * step declares as {@code sql.keys:}. Empty when the deployment's create supplies its own id.
     */
    static java.util.List<String> readKeys(Object value) {
        if (value == null) {
            return java.util.List.of();
        }
        if (value instanceof java.util.List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return java.util.Arrays.stream(String.valueOf(value).split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList();
    }

    /**
     * The main datasource's dialect, resolved exactly as the runtime resolves it (the declared
     * {@code dialect} key, else the JDBC URL). It steers the generated-key JDBC call; SCIM's
     * result labels stay raw regardless (quoted camelCase aliases).
     */
    private static String dialect(AppConfig config) {
        String prefix = "tesseraql.datasources.main.";
        return config.getString(prefix + "dialect")
                .orElseGet(() -> io.tesseraql.core.dialect.Dialect
                        .fromJdbcUrl(config.getString(prefix + "jdbcUrl").orElse(""))
                        .map(io.tesseraql.core.dialect.Dialect::id)
                        .orElse(null));
    }

    /**
     * The identity-store attribute capture (docs/application-roles.md structural decision 3),
     * on when {@code tesseraql.scim.attributes.enabled} is true: the enterprise extension's org
     * attributes plus {@code tesseraql.scim.attributes.map} (SCIM path → attribute name) land in
     * {@code tql_user_attributes}, keyed by the SCIM resource id. A realm that cannot take the
     * writes is a boot-time configuration error, not a silent drop.
     */
    private static io.tesseraql.scim.ScimAttributeCapture buildAttributeCapture(
            ExtensionContext context) {
        AppConfig config = context.manifest().config();
        if (!flag(config, "tesseraql.scim.attributes.enabled")) {
            return null;
        }
        io.tesseraql.identity.IdentityService identity = context.bean(
                TesseraqlProperties.IDENTITY_SERVICE_BEAN,
                io.tesseraql.identity.IdentityService.class);
        io.tesseraql.identity.RealmConfig realm = context.bean(
                TesseraqlProperties.IDENTITY_REALM_BEAN,
                io.tesseraql.identity.RealmConfig.class);
        if (identity == null || realm == null || !realm.capabilities().userWriteAllowed()) {
            throw new IllegalStateException("tesseraql.scim.attributes.enabled requires an "
                    + "identity realm with user-management writes (a managed realm)");
        }
        java.util.Map<String, String> extras = new java.util.LinkedHashMap<>();
        if (config
                .navigate("tesseraql.scim.attributes.map") instanceof java.util.Map<?, ?> entries) {
            entries.forEach((path, name) -> extras.put(String.valueOf(path),
                    String.valueOf(name)));
        }
        return new io.tesseraql.scim.ScimAttributeCapture(identity, realm, extras);
    }

    private static String readSql(AppManifest manifest, String configKey) {
        String relative = manifest.config().requireString(configKey);
        try {
            return Files.readString(manifest.appHome().resolve(relative).normalize());
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read SCIM contract SQL: " + relative, ex);
        }
    }

    /** Reads an optional SCIM contract SQL file, returning {@code null} when the key is unset. */
    private static String readSqlOptional(AppManifest manifest, String configKey) {
        return manifest.config().getString(configKey).isPresent()
                ? readSql(manifest, configKey)
                : null;
    }

    private static boolean flag(AppConfig config, String key) {
        return config.getString(key).map(Boolean::parseBoolean).orElse(false);
    }
}
