package io.tesseraql.core.dialect;

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

/**
 * How a JDBC column value is read: in the kind the database declares for it
 * (docs/temporal-semantics.md, decision 13).
 *
 * <p>A wall clock — a zoneless {@code timestamp}, {@code datetime}, {@code datetime2}, Oracle's
 * {@code TIMESTAMP} and {@code DATE} — is read as a {@link LocalDateTime}; an instant — a
 * {@code timestamptz}, {@code datetimeoffset}, {@code TIMESTAMP WITH [LOCAL] TIME ZONE} — as an
 * {@link OffsetDateTime}; a date as a {@link LocalDate}, a time of day as a {@link LocalTime}, a
 * time with zone as an {@link OffsetTime}. Read that way, a value does not depend on the JVM's
 * zone: {@code getObject(i)} hands a wall clock over as a {@code java.sql.Timestamp} built in the
 * JVM's zone, which then moves with the host — and inside the host zone's DST gap moves by an
 * hour with no zone involved at all. Measured on all five supported drivers under two JVM zones
 * (the design record's matrix).
 *
 * <p>Which read to try comes from the JDBC type code, and the order is the two traps' order:
 * for {@link Types#TIMESTAMP} a {@code LocalDateTime} FIRST, because reading a zoneless column
 * as an {@code OffsetDateTime} does not fail — pgjdbc answers it as UTC and Connector/J with
 * the JVM's own offset, a different instant per host. pgjdbc reports {@code timestamptz} under
 * the same code and refuses the wall-clock read for it, which is the one signal that column is
 * an instant; the refusal is remembered per column, so a million-row export pays for it once.
 * A driver may also refuse a typed read while its default is already the right class (DuckDB
 * for {@code LocalTime} and {@code OffsetTime}), so the last resort is {@code getObject(i)} with
 * the legacy {@code java.sql} classes converted to their {@code java.time} kind.
 *
 * <p>Dependency-free on purpose: the type codes are {@link Types} constants plus the three vendor
 * codes for zoned timestamps, never a driver class name.
 */
public final class JdbcValues {

    /** SQL Server's {@code datetimeoffset} ({@code microsoft.sql.Types.DATETIMEOFFSET}). */
    private static final int MSSQL_DATETIMEOFFSET = -155;
    /** Oracle's {@code TIMESTAMP WITH TIME ZONE} ({@code oracle.jdbc.OracleTypes.TIMESTAMPTZ}). */
    private static final int ORACLE_TIMESTAMPTZ = -101;
    /** Oracle's {@code TIMESTAMP WITH LOCAL TIME ZONE} ({@code OracleTypes.TIMESTAMPLTZ}). */
    private static final int ORACLE_TIMESTAMPLTZ = -102;

    private JdbcValues() {
    }

    /** A reader over one result set's columns, remembering which read each took. */
    public static Reader reader(ResultSetMetaData metaData) throws SQLException {
        int count = metaData.getColumnCount();
        int[] types = new int[count + 1];
        for (int column = 1; column <= count; column++) {
            types[column] = metaData.getColumnType(column);
        }
        return new Reader(types);
    }

    /**
     * The {@code java.time} kind of a value another path read with {@code getObject}: the legacy
     * {@code java.sql} temporals converted, everything else unchanged. A {@code Timestamp} is a
     * wall clock here — the JVM-zone wall clock the driver built it from — which is what a
     * zoneless column holds and the best an untyped read can recover for one.
     */
    public static Object normalize(Object value) {
        return switch (value) {
            case null -> null;
            case java.sql.Timestamp timestamp -> timestamp.toLocalDateTime();
            case java.sql.Date date -> date.toLocalDate();
            case java.sql.Time time -> time.toLocalTime();
            case java.util.Date date -> date.toInstant().atOffset(ZoneOffset.UTC);
            default -> value;
        };
    }

    /** The per-column strategy: the typed read to try, and whether the driver refused it. */
    public static final class Reader {

        private static final byte UNTRIED = 0;
        private static final byte TYPED = 1;
        private static final byte SECOND = 2;
        private static final byte LEGACY = 3;

        private final int[] types;
        private final byte[] strategy;

        private Reader(int[] types) {
            this.types = types;
            this.strategy = new byte[types.length];
        }

        /** The column's value in its kind. */
        public Object read(ResultSet resultSet, int column) throws SQLException {
            Class<?> first = firstRead(types[column]);
            if (first == null) {
                return normalize(resultSet.getObject(column));
            }
            Class<?> second = secondRead(types[column]);
            byte how = strategy[column];
            if (how == UNTRIED || how == TYPED) {
                try {
                    Object value = resultSet.getObject(column, first);
                    strategy[column] = TYPED;
                    return value;
                } catch (SQLException refused) {
                    strategy[column] = second == null ? LEGACY : SECOND;
                }
            }
            if (strategy[column] == SECOND) {
                try {
                    return resultSet.getObject(column, second);
                } catch (SQLException refused) {
                    strategy[column] = LEGACY;
                }
            }
            return normalize(resultSet.getObject(column));
        }

        /** The read a column of this JDBC type takes first; null for a column read as it comes. */
        private static Class<?> firstRead(int jdbcType) {
            return switch (jdbcType) {
                case Types.DATE -> LocalDate.class;
                case Types.TIME -> LocalTime.class;
                case Types.TIMESTAMP -> LocalDateTime.class;
                case Types.TIME_WITH_TIMEZONE -> OffsetTime.class;
                case Types.TIMESTAMP_WITH_TIMEZONE, MSSQL_DATETIMEOFFSET, ORACLE_TIMESTAMPTZ,
                        ORACLE_TIMESTAMPLTZ ->
                    OffsetDateTime.class;
                default -> null;
            };
        }

        /**
         * The read a refusal of the first one means: a {@code TIMESTAMP} the driver will not hand
         * over as a wall clock is an instant (pgjdbc's {@code timestamptz}), a {@code TIME} it
         * will not hand over as a time of day carries a zone (pgjdbc's {@code timetz}).
         */
        private static Class<?> secondRead(int jdbcType) {
            return switch (jdbcType) {
                case Types.TIMESTAMP -> OffsetDateTime.class;
                case Types.TIME -> OffsetTime.class;
                default -> null;
            };
        }
    }
}
