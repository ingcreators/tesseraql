package io.tesseraql.scim;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The bundled managed Group contract set (docs/contract-sql-execution.md structural decision 6):
 * nine statements against the managed identity schema's {@code tql_groups} /
 * {@code tql_user_groups}, dialect-suffixed only where pagination forces it. A deployment gets it
 * by setting {@code tesseraql.scim.groups.enabled} and configuring none of the per-operation SQL
 * keys; declaring all of them means the deployment's own schema, and anything in between is a
 * boot refusal, never two schemas mixed one statement at a time.
 *
 * <p>The mapping: {@code id} → {@code group_id}, {@code displayName} → {@code group_name}
 * <em>and</em> {@code group_code} (the code is what assignment rules join on, so it is the name
 * an administrator recognises), {@code externalId} → {@code external_id}. Ids are minted here as
 * {@code grp-<uuid>} — the managed store's own shape — because {@code group_id} is a supplied
 * column and there is nothing for a database to generate.
 */
public final class ScimGroupPack {

    private static final List<String> PAGINATION_VARIANTS = List.of("oracle", "sqlserver");

    private ScimGroupPack() {
    }

    /** The bundled contract for {@code dialect} (null falls back to the base statements). */
    public static ScimGroupContract contract(String dialect) {
        return new ScimGroupContract(
                read("create.sql"),
                read("find-by-id.sql"),
                read(listResource(dialect)),
                read("replace.sql"),
                read("delete.sql"),
                read("list-members.sql"),
                read("add-member.sql"),
                read("remove-member.sql"),
                read("count.sql"),
                List.of());
    }

    /** The id the managed store mints: {@code grp-<uuid>}, known before the insert. */
    public static Supplier<String> idSupplier() {
        return () -> "grp-" + UUID.randomUUID();
    }

    private static String listResource(String dialect) {
        return dialect != null && PAGINATION_VARIANTS.contains(dialect)
                ? "list." + dialect + ".sql"
                : "list.sql";
    }

    private static String read(String name) {
        String path = "/io/tesseraql/scim/pack/groups/" + name;
        try (InputStream in = ScimGroupPack.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Bundled SCIM group SQL missing: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException("Bundled SCIM group SQL unreadable: " + path,
                    unreadable);
        }
    }
}
