package io.tesseraql.identity;

import java.nio.file.Path;

/**
 * Configuration of an identity realm (design ch. 10.2).
 *
 * @param id         the realm id, e.g. {@code local} or {@code legacy}
 * @param type       {@link RealmType}
 * @param datasource the datasource name backing the realm
 * @param sqlRoot    for {@code sql} realms, the directory of contract SQL files; null for managed
 */
public record RealmConfig(String id, RealmType type, String datasource, Path sqlRoot,
        Capabilities capabilities) {

    public RealmConfig {
        capabilities = capabilities == null ? Capabilities.readOnly() : capabilities;
    }

    /** Realm backing type (design ch. 10.2). */
    public enum RealmType {
        /** Uses the TesseraQL standard schema and the default identity pack SQL. */
        MANAGED,
        /** Connects to an existing DB via app-provided contract SQL. */
        SQL
    }

    public static RealmConfig managed(String id, String datasource) {
        return new RealmConfig(id, RealmType.MANAGED, datasource, null, Capabilities.readWrite());
    }

    public static RealmConfig sql(String id, String datasource, Path sqlRoot) {
        return new RealmConfig(id, RealmType.SQL, datasource, sqlRoot, Capabilities.readOnly());
    }

    public static RealmConfig managed(String id, String datasource, Capabilities capabilities) {
        return new RealmConfig(id, RealmType.MANAGED, datasource, null, capabilities);
    }

    public static RealmConfig sql(String id, String datasource, Path sqlRoot, Capabilities capabilities) {
        return new RealmConfig(id, RealmType.SQL, datasource, sqlRoot, capabilities);
    }
}
