package io.tesseraql.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import org.junit.jupiter.api.Test;

/** The no-op expect: guard (silent-tolerance K2). */
class TestSuiteLoaderTest {

    @Test
    void rejectsAnExpectBlockThatAssertsNothing() {
        // `rowcount:` is a typo for rowCount:; ignoreUnknown drops it, leaving an all-null
        // expect that passes green while asserting nothing.
        assertThatThrownBy(() -> new TestSuiteLoader().parse("""
                version: tesseraql/v1
                tests:
                  - name: search returns rows
                    sql:
                      file: search.sql
                    expect:
                      rowcount: 3
                """)).isInstanceOf(TqlException.class).hasMessageContaining("asserts nothing");
    }

    @Test
    void acceptsACaseWithNoExpectBlock() {
        // Omitting expect: entirely is the supported "just assert it runs" idiom.
        TestSuite suite = new TestSuiteLoader().parse("""
                version: tesseraql/v1
                tests:
                  - name: runs without error
                    sql:
                      file: search.sql
                """);
        assertThat(suite.tests()).hasSize(1);
    }

    @Test
    void acceptsAWellFormedExpectBlock() {
        TestSuite suite = new TestSuiteLoader().parse("""
                version: tesseraql/v1
                tests:
                  - name: search returns rows
                    sql:
                      file: search.sql
                    expect:
                      rowCount: 3
                """);
        assertThat(suite.tests()).singleElement()
                .satisfies(test -> assertThat(test.expect().rowCount()).isEqualTo(3));
    }
}
