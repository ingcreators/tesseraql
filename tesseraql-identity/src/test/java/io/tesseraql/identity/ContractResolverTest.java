package io.tesseraql.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class ContractResolverTest {

    @Test
    void sqlRealmPrefersDialectVariant(@TempDir Path sqlRoot) throws Exception {
        Files.writeString(sqlRoot.resolve("find-user-by-login.sql"), "BASE");
        Files.writeString(sqlRoot.resolve("find-user-by-login.mysql.sql"), "MYSQL");
        RealmConfig realm = RealmConfig.sql("legacy", "main", sqlRoot);

        assertThat(new ContractResolver(realm, "mysql").resolve("find-user-by-login"))
                .isEqualTo("MYSQL");
        // No variant for postgres -> base; and no dialect -> base.
        assertThat(new ContractResolver(realm, "postgres").resolve("find-user-by-login"))
                .isEqualTo("BASE");
        assertThat(new ContractResolver(realm).resolve("find-user-by-login")).isEqualTo("BASE");
    }

    @Test
    void managedRealmFallsBackToBaseWhenNoVariant() {
        RealmConfig realm = RealmConfig.managed("local", "main");
        // The default pack ships only the base (postgres) contract; an unknown dialect falls back.
        assertThat(new ContractResolver(realm, "mysql").resolve("find-user-by-login"))
                .contains("tql_users");
    }
}
