package io.tesseraql.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code lock:} costs at compile time (docs/edit-conflict.md decision 1): the lock step is
 * mounted ahead of the write, and every way the declaration can fail to mean what it says is
 * refused where it is written rather than accepted and quietly doing nothing.
 */
class LockDeclarationTest {

    private static final String LOCKED_SQL = """
            update items
               set name = /* name */ 'x',
                   version = version + 1
             where id = /* id */ 0
               and /*%lock*/ (1=1)
            """;

    private static final String UNLOCKED_SQL = """
            update items set name = /* name */ 'x' where id = /* id */ 0
            """;

    @Test
    void aLockedRouteMountsTheLockStepBeforeTheWrite(@TempDir Path dir) throws Exception {
        List<String> steps = compile(dir, "lock: version\n", LOCKED_SQL).get("items.update");

        assertThat(steps).contains("LockBinder");
        // Before the write, so a malformed caller never opens the transaction.
        assertThat(steps.indexOf("LockBinder"))
                .isLessThan(steps.indexOf("TransactionalCommandProcessor"));
    }

    @Test
    void anUnlockedRouteMountsNoLockStep(@TempDir Path dir) throws Exception {
        assertThat(compile(dir, "", UNLOCKED_SQL).get("items.update"))
                .doesNotContain("LockBinder");
    }

    @Test
    void aLockColumnMustBeASqlIdentifier(@TempDir Path dir) {
        // The column is interpolated into the statement's text, so this is the injection boundary.
        assertThatThrownBy(() -> compile(dir, "lock: \"version = 1 or 1\"\n", LOCKED_SQL))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-ROUTE-3119")
                .hasMessageContaining("SQL identifier");
    }

    @Test
    void aLockDeclaredWithNoDirectiveIsRefused(@TempDir Path dir) {
        // Direction A of the pairing: a lock that compares nothing is a lock that is not one.
        assertThatThrownBy(() -> compile(dir, "lock: version\n", UNLOCKED_SQL))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-ROUTE-3119")
                .hasMessageContaining("no step's statement carries the lock directive");
    }

    @Test
    void aDirectiveWithNoLockDeclaredIsRefused(@TempDir Path dir) {
        // Direction B, refused by the processor — which already holds the parse.
        assertThatThrownBy(() -> compile(dir, "", LOCKED_SQL))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-ROUTE-3102")
                .hasMessageContaining("needs a route-level lock:");
    }

    @Test
    void expectCannotBeDeclaredBesideTheLock(@TempDir Path dir) {
        assertThatThrownBy(() -> compile(dir, "lock: version\n", LOCKED_SQL, """
                      expect:
                        rowCount: 1
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-ROUTE-3102")
                .hasMessageContaining("cannot be declared beside a lock directive");
    }

    @Test
    void aSourceDeclaringExpectIsRefused(@TempDir Path dir) {
        // expect: under a read acquisition validates today and does nothing at all.
        assertThatThrownBy(() -> compile(dir, """
                sources:
                  extra:
                    sql:
                      file: read.sql
                      expect:
                        rowCount: 1
                """, UNLOCKED_SQL))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-ROUTE-3120")
                .hasMessageContaining("expect:");
    }

    @Test
    void aConditionalLockIsRefused(@TempDir Path dir) {
        // A directive inside an /*%if*/ renders away on the branch that omits it, and the write
        // would then meet its own implied row-count expectation with no lock predicate at all —
        // the silently unlocked write this whole surface exists to abolish.
        assertThatThrownBy(() -> compile(dir, "lock: version\n", """
                update items
                   set name = /* name */ 'x', version = version + 1
                 where id = /* id */ 0
                   /*%if name != null */ and /*%lock*/ (1=1) /*%end*/
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-ROUTE-3102")
                .hasMessageContaining("is not conditional");
    }

    @Test
    void aSecondCarrierStepIsRefused(@TempDir Path dir) {
        // One route, one lock: two carriers would compare one submitted value against both.
        assertThatThrownBy(() -> compile(dir, "command-json", "lock: version\n", LOCKED_SQL,
                "", """
                          - id: second
                            sql:
                              file: update.sql
                              params:
                                id: params.id
                                name: params.name
                        """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-ROUTE-3102")
                .hasMessageContaining("already carries the route's one lock");
    }

    @Test
    void aLockOnAReadRecipeIsRefused(@TempDir Path dir) {
        assertThatThrownBy(() -> compile(dir, "query-json", "lock: version\n", LOCKED_SQL,
                "", ""))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-ROUTE-3119")
                .hasMessageContaining("HTTP command route");
    }

    @Test
    void aLockOnANonUpdateStepIsRefused(@TempDir Path dir) {
        // The implied expectation counts affected rows, which a query never has.
        assertThatThrownBy(() -> compile(dir, "lock: version\n", LOCKED_SQL, """
                      mode: query
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-ROUTE-3102")
                .hasMessageContaining("needs an update statement");
    }

    @Test
    void theBlockFormDeclaresTheColumnType(@TempDir Path dir) throws Exception {
        assertThat(compile(dir, "lock: { column: version, type: integer }\n", LOCKED_SQL))
                .containsKey("items.update");
    }

    @Test
    void aLockedRouteNoFormTargetsStillCompiles(@TempDir Path dir) throws Exception {
        // Decision 3's API-caller case: a lock is legal on a route no view renders.
        assertThat(compile(dir, "lock: version\n", LOCKED_SQL)).containsKey("items.update");
    }

    private static Map<String, List<String>> compile(Path dir, String body, String sql)
            throws Exception {
        return compile(dir, body, sql, "");
    }

    private static Map<String, List<String>> compile(Path dir, String body, String sql,
            String stepExtras) throws Exception {
        return compile(dir, "command-json", body, sql, stepExtras, "");
    }

    private static Map<String, List<String>> compile(Path dir, String recipe, String body,
            String sql, String stepExtras, String extraSteps) throws Exception {
        writeApp(dir, recipe, body, sql, stepExtras, extraSteps);
        AppManifest manifest = new ManifestLoader().load(dir);
        try (RuntimeContext context = new RuntimeContext()) {
            new RouteCompiler().appName("lock-test").compile(context, manifest, false, null);
            return CompiledPipelines.stepsById(context);
        }
    }

    private static void writeApp(Path dir, String recipe, String body, String sql,
            String stepExtras, String extraSteps) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: lock-test
                """);
        Path route = Files.createDirectories(dir.resolve("web/items/update"));
        Files.writeString(route.resolve("post.yml"), """
                version: tesseraql/v1
                id: items.update
                kind: route
                """ + "recipe: " + recipe + "\n" + """
                input:
                  id: { type: integer, required: true }
                  name: { type: string }
                """ + body + """
                steps:
                  - id: main
                    sql:
                      file: update.sql
                """ + stepExtras + """
                      params:
                        id: params.id
                        name: params.name
                """ + extraSteps + """
                response:
                  json:
                    status: 200
                """);
        Files.writeString(route.resolve("update.sql"), sql);
        Files.writeString(route.resolve("read.sql"), "select 1 from items\n");
    }
}
