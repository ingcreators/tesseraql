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

    @Test
    void unknownAndNullUseConservativeDefault() {
        assertThat(StreamingProfiles.forDialect("oracle").fetchSize()).isEqualTo(1000);
        assertThat(StreamingProfiles.forDialect(null).fetchSize()).isEqualTo(1000);
        assertThat(StreamingProfiles.forDialect("db2").autoCommitOff()).isFalse();
    }
}
