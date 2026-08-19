package io.tesseraql.scim.camel;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.compiler.ext.ExtensionContext;
import io.tesseraql.compiler.ext.RuntimeExtension;
import io.tesseraql.core.outbox.OutboxEventSink;
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
            context.camel().addRoutes(new ScimRouteBuilder(
                    buildUserService(manifest, context.dataSource()),
                    buildGroupService(manifest, context.dataSource()),
                    buildAttributeCapture(context)));
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

    /** Builds the SCIM user service from the configured contract SQL files (design ch. 10.15). */
    private static ScimUserService buildUserService(
            AppManifest manifest, javax.sql.DataSource dataSource) {
        ScimContract contract = new ScimContract(
                readSql(manifest, "tesseraql.scim.users.create"),
                readSql(manifest, "tesseraql.scim.users.findById"),
                readSql(manifest, "tesseraql.scim.users.list"),
                readSql(manifest, "tesseraql.scim.users.replace"),
                readSql(manifest, "tesseraql.scim.users.delete"),
                readSql(manifest, "tesseraql.scim.users.findByUserName"),
                readSqlOptional(manifest, "tesseraql.scim.users.count"));
        return new ScimUserService(dataSource, contract);
    }

    /**
     * Builds the SCIM group service from the configured contract SQL files, or {@code null} when
     * group provisioning is disabled, leaving the {@code /Groups} endpoints unmounted.
     */
    private static ScimGroupService buildGroupService(
            AppManifest manifest, javax.sql.DataSource dataSource) {
        if (!flag(manifest.config(), "tesseraql.scim.groups.enabled")) {
            return null;
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
                readSqlOptional(manifest, "tesseraql.scim.groups.count"));
        return new ScimGroupService(dataSource, contract);
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
