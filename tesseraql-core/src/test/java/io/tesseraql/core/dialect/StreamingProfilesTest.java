package io.tesseraql.core.dialect;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StreamingProfilesTest {

    @Test
    void postgresStreamsWithCursorAndAutoCommitOff() {
        StreamingProfile profile = StreamingProfiles.forDialect("postgres");
        assertThat(profile.autoCommitOff()).isTrue();
        assertThat(profile.fetchSize()).isPositive();
    }

    @Test
    void mysqlStreamsRowByRow() {
        StreamingProfile profile = StreamingProfiles.forDialect("mysql");
        assertThat(profile.fetchSize()).isEqualTo(Integer.MIN_VALUE);
        assertThat(profile.autoCommitOff()).isFalse();
    }

    /**
     * MariaDB Connector/J refuses {@code setFetchSize(Integer.MIN_VALUE)} outright, so speaking
     * MySQL's protocol does not make MySQL's profile correct.
     * {@code StreamingProfileDriverIntegrationTest} is what proves this against the real driver;
     * this only pins the mapping so the two cannot silently merge again.
     */
    @Test
    void mariadbDoesNotTakeMysqlsRowStreamingSignal() {
        StreamingProfile profile = StreamingProfiles.forDialect("mariadb");
        assertThat(profile.fetchSize()).isPositive();
        assertThat(profile.autoCommitOff()).isFalse();
    }

    @Test
    void unknownAndNullUseConservativeDefault() {
        assertThat(StreamingProfiles.forDialect("oracle").fetchSize()).isEqualTo(1000);
        assertThat(StreamingProfiles.forDialect(null).fetchSize()).isEqualTo(1000);
        assertThat(StreamingProfiles.forDialect("db2").autoCommitOff()).isFalse();
    }
}
