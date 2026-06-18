package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import org.junit.jupiter.api.Test;

class MigrationDdlTest {

    @Test
    void addColumnEmitsTypeDefaultAndNotNullInOrder() {
        assertThat(MigrationDdl.addColumn("users", "nickname", "text", true, ""))
                .isEqualTo("ALTER TABLE users ADD COLUMN nickname text;\n");
        assertThat(MigrationDdl.addColumn("accounts", "balance", "numeric(10,2)", false, "0"))
                .isEqualTo(
                        "ALTER TABLE accounts ADD COLUMN balance numeric(10,2) DEFAULT 0 NOT NULL;\n");
    }

    @Test
    void createIndexDefaultsItsNameAndHonoursUnique() {
        assertThat(MigrationDdl.createIndex("users", "name, status", false, ""))
                .isEqualTo("CREATE INDEX users_name_status_idx ON users (name, status);\n");
        assertThat(MigrationDdl.createIndex("users", "email", true, "users_email_unique"))
                .isEqualTo("CREATE UNIQUE INDEX users_email_unique ON users (email);\n");
    }

    @Test
    void requiredFieldsAndSingleStatementAreEnforced() {
        assertThatThrownBy(() -> MigrationDdl.addColumn(" ", "c", "text", true, ""))
                .isInstanceOf(TqlException.class).hasMessageContaining("table is required");
        assertThatThrownBy(() -> MigrationDdl.createIndex("t", "", false, ""))
                .isInstanceOf(TqlException.class).hasMessageContaining("columns is required");
        // An embedded ';' is rejected so one builder action yields one statement.
        assertThatThrownBy(() -> MigrationDdl.addColumn("t", "c", "text; drop table t", true, ""))
                .isInstanceOf(TqlException.class).hasMessageContaining("single value");
    }
}
