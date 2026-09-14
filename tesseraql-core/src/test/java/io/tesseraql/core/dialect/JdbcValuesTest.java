package io.tesseraql.core.dialect;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A column is read in the kind the database declares for it (docs/temporal-semantics.md,
 * decision 13), through a result set that behaves like each measured driver: it answers the
 * typed reads it supports, refuses the ones it does not, and hands {@code getObject} over as the
 * driver would. The fake logs every read, so the ORDER of reads is asserted, not only the value
 * — reading a zoneless column as an {@code OffsetDateTime} first does not fail on any driver,
 * it answers a different instant per host.
 */
class JdbcValuesTest {

    /** pgjdbc's zoneless {@code timestamp}: both reads answer, one is right. */
    @Test
    void aZonelessTimestampIsReadAsAWallClockFirst() throws SQLException {
        LocalDateTime wallClock = LocalDateTime.parse("2026-03-08T02:30");
        Fake column = Fake.column(Types.TIMESTAMP, Map.of(
                LocalDateTime.class, wallClock,
                OffsetDateTime.class, wallClock.atOffset(ZoneOffset.ofHours(-8))),
                java.sql.Timestamp.valueOf("2026-03-08 03:30:00"));

        assertThat(JdbcValues.reader(column.metaData()).read(column.resultSet(), 1))
                .isEqualTo(wallClock);
        assertThat(column.reads).containsExactly("LocalDateTime");
    }

    /** pgjdbc's {@code timestamptz}: the same type code, the wall-clock read refused. */
    @Test
    void aTimestampTheDriverWillNotHandOverAsAWallClockIsAnInstant() throws SQLException {
        OffsetDateTime instant = OffsetDateTime.parse("2026-01-15T22:30Z");
        Fake column = Fake.column(Types.TIMESTAMP, Map.of(OffsetDateTime.class, instant),
                java.sql.Timestamp.from(instant.toInstant()));
        JdbcValues.Reader reader = JdbcValues.reader(column.metaData());

        assertThat(reader.read(column.resultSet(), 1)).isEqualTo(instant);
        assertThat(reader.read(column.resultSet(), 1)).isEqualTo(instant);
        // The refusal is remembered: the second row never asks for the wall clock again.
        assertThat(column.reads).containsExactly("LocalDateTime", "OffsetDateTime",
                "OffsetDateTime");
    }

    /** DuckDB's {@code time}: every typed read refused, the default already the right kind. */
    @Test
    void aDriverThatRefusesTheTypedReadsIsReadAsItComes() throws SQLException {
        LocalTime time = LocalTime.parse("22:30:00.5");
        Fake column = Fake.column(Types.TIME, Map.of(), time);

        assertThat(JdbcValues.reader(column.metaData()).read(column.resultSet(), 1))
                .isEqualTo(time);
        assertThat(column.reads).containsExactly("LocalTime", "OffsetTime", "getObject");
    }

    /** pgjdbc's {@code timetz} under {@code TIME}: time of day refused, the zoned read taken. */
    @Test
    void aTimeTheDriverWillNotHandOverAsATimeOfDayCarriesItsZone() throws SQLException {
        OffsetTime time = OffsetTime.parse("22:30:00+09:00");
        Fake column = Fake.column(Types.TIME, Map.of(OffsetTime.class, time),
                java.sql.Time.valueOf("13:30:00"));

        assertThat(JdbcValues.reader(column.metaData()).read(column.resultSet(), 1))
                .isEqualTo(time);
    }

    @Test
    void theZonedTypeCodesAreReadAsInstants() throws SQLException {
        OffsetDateTime instant = OffsetDateTime.parse("2026-01-15T22:30Z");
        for (int code : new int[]{Types.TIMESTAMP_WITH_TIMEZONE, -155, -101, -102}) {
            Fake column = Fake.column(code, Map.of(OffsetDateTime.class, instant), "hash");
            assertThat(JdbcValues.reader(column.metaData()).read(column.resultSet(), 1))
                    .as("type code " + code).isEqualTo(instant);
            assertThat(column.reads).as("type code " + code).containsExactly("OffsetDateTime");
        }
    }

    @Test
    void aDateIsALocalDateAndATimeWithZoneAnOffsetTime() throws SQLException {
        LocalDate date = LocalDate.parse("2026-01-15");
        OffsetTime time = OffsetTime.parse("22:30:00+09:00");
        Fake dateColumn = Fake.column(Types.DATE, Map.of(LocalDate.class, date),
                java.sql.Date.valueOf(date));
        Fake timeColumn = Fake.column(Types.TIME_WITH_TIMEZONE, Map.of(OffsetTime.class, time),
                time);

        assertThat(JdbcValues.reader(dateColumn.metaData()).read(dateColumn.resultSet(), 1))
                .isEqualTo(date);
        assertThat(JdbcValues.reader(timeColumn.metaData()).read(timeColumn.resultSet(), 1))
                .isEqualTo(time);
    }

    /** A column of any other type is what {@code getObject} says, a legacy temporal converted. */
    @Test
    void anyOtherColumnIsReadAsItComesWithLegacyTemporalsConverted() throws SQLException {
        Fake text = Fake.column(Types.VARCHAR, Map.of(), "alpha");
        Fake legacy = Fake.column(Types.OTHER, Map.of(),
                java.sql.Timestamp.valueOf("2026-01-15 22:30:00"));

        assertThat(JdbcValues.reader(text.metaData()).read(text.resultSet(), 1))
                .isEqualTo("alpha");
        assertThat(text.reads).containsExactly("getObject");
        assertThat(JdbcValues.reader(legacy.metaData()).read(legacy.resultSet(), 1))
                .isEqualTo(LocalDateTime.parse("2026-01-15T22:30"));
    }

    @Test
    void aNullIsNullThroughEveryRead() throws SQLException {
        Fake typed = Fake.column(Types.TIMESTAMP, Map.of(), null);
        assertThat(JdbcValues.reader(typed.metaData()).read(typed.resultSet(), 1)).isNull();
    }

    @Test
    void normalizeConvertsTheLegacyClassesAndLeavesTheRestAlone() {
        assertThat(JdbcValues.normalize(java.sql.Date.valueOf("2026-01-15")))
                .isEqualTo(LocalDate.parse("2026-01-15"));
        assertThat(JdbcValues.normalize(java.sql.Time.valueOf("22:30:00")))
                .isEqualTo(LocalTime.parse("22:30"));
        assertThat(JdbcValues.normalize(new java.util.Date(0)))
                .isEqualTo(OffsetDateTime.parse("1970-01-01T00:00Z"));
        assertThat(JdbcValues.normalize("text")).isEqualTo("text");
        assertThat(JdbcValues.normalize(null)).isNull();
    }

    /**
     * One column of one JDBC type: the typed reads the driver supports (a missing class is a
     * refusal, as the drivers refuse — with an {@link SQLException}), and the default object.
     */
    private static final class Fake {

        final List<String> reads = new ArrayList<>();
        private final int type;
        private final Map<Class<?>, Object> typed;
        private final Object raw;

        private Fake(int type, Map<Class<?>, Object> typed, Object raw) {
            this.type = type;
            this.typed = typed;
            this.raw = raw;
        }

        static Fake column(int type, Map<Class<?>, Object> typed, Object raw) {
            return new Fake(type, typed, raw);
        }

        ResultSetMetaData metaData() {
            return (ResultSetMetaData) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSetMetaData.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getColumnCount" -> 1;
                        case "getColumnType" -> type;
                        default -> throw new UnsupportedOperationException(
                                method.getName());
                    });
        }

        ResultSet resultSet() {
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, args) -> {
                        if (!"getObject".equals(method.getName())) {
                            throw new UnsupportedOperationException(method.getName());
                        }
                        if (args.length == 1) {
                            reads.add("getObject");
                            return raw;
                        }
                        Class<?> asked = (Class<?>) args[1];
                        reads.add(asked.getSimpleName());
                        if (raw == null) {
                            return null;
                        }
                        if (!typed.containsKey(asked)) {
                            throw new SQLException("Can't convert value to " + asked);
                        }
                        return typed.get(asked);
                    });
        }
    }
}
