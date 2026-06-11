package io.tesseraql.core.dialect;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PaginationTest {

    @Test
    void limitOffsetDialectsUseLimitOffset() {
        Pagination.Clause postgres = Pagination.clause(Dialect.POSTGRES, 10, 20);
        assertThat(postgres.sql()).isEqualTo("limit ? offset ?");
        assertThat(postgres.parameters()).containsExactly(10L, 20L);

        Pagination.Clause mysql = Pagination.clause(Dialect.MYSQL, 10, 20);
        assertThat(mysql.sql()).isEqualTo("limit ? offset ?");
        assertThat(mysql.parameters()).containsExactly(10L, 20L);
    }

    @Test
    void offsetFetchDialectsUseOffsetFetchWithSwappedParams() {
        Pagination.Clause oracle = Pagination.clause(Dialect.ORACLE, 10, 20);
        assertThat(oracle.sql()).isEqualTo("offset ? rows fetch next ? rows only");
        assertThat(oracle.parameters()).containsExactly(20L, 10L);

        Pagination.Clause sqlServer = Pagination.clause(Dialect.SQLSERVER, 10, 20);
        assertThat(sqlServer.sql()).isEqualTo("offset ? rows fetch next ? rows only");
        assertThat(sqlServer.parameters()).containsExactly(20L, 10L);
    }
}
