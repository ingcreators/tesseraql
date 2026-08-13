package io.tesseraql.core.files;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.spool.SpoolKind;
import io.tesseraql.core.spool.SpoolRef;
import io.tesseraql.core.spool.SpoolWriter;
import io.tesseraql.core.spool.TempStore;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * A row sequence that can be walked more than once without being held in memory
 * (docs/export-pipeline.md, decision 8): the source iterator is drained into a {@link TempStore}
 * spool exactly once, and every {@link #iterator()} opens a fresh reader over it.
 *
 * <p>This is what a buffering codec receives in place of a single-pass iterator — a jxls report
 * template may iterate its rows more than once and group them — and what the framework's own
 * grouping reads. The store is the one the runtime already provisions, so a deployment that spools
 * to the database keeps doing so.
 *
 * <p><strong>The cost here is type fidelity, not storage.</strong> Rows carry raw JDBC values, and
 * a lossy round trip changes an existing report's output: a numeric cell becomes text, a date cell
 * loses its format. So the encoding is tagged rather than textual, one type byte per value, and a
 * value it cannot represent fails with the column named instead of degrading to its
 * {@code toString()}. JSON was rejected for being lossy exactly where it matters (numeric scale,
 * temporal type), and Java serialization for accepting arbitrary object graphs from a driver.
 */
public final class SpooledRows implements Iterable<Map<String, Object>>, AutoCloseable {

    /** TQL-LD-2853: a row value has no representation in the spool encoding. */
    static final TqlErrorCode UNREPRESENTABLE = new TqlErrorCode(TqlDomain.LD, 2853);
    /** TQL-LD-2854: a row's columns differ from the first row's. */
    static final TqlErrorCode SHAPE_CHANGED = new TqlErrorCode(TqlDomain.LD, 2854);
    /** TQL-LD-2855: the spool could not be written or read back. */
    static final TqlErrorCode SPOOL_FAILED = new TqlErrorCode(TqlDomain.LD, 2855);

    private static final byte NULL = 0;
    private static final byte STRING = 1;
    private static final byte BOOLEAN = 2;
    private static final byte LONG = 3;
    private static final byte INTEGER = 4;
    private static final byte SHORT = 5;
    private static final byte BYTE = 6;
    private static final byte DOUBLE = 7;
    private static final byte FLOAT = 8;
    private static final byte BIG_DECIMAL = 9;
    private static final byte LOCAL_DATE = 10;
    private static final byte LOCAL_TIME = 11;
    private static final byte LOCAL_DATE_TIME = 12;
    private static final byte INSTANT = 13;
    private static final byte OFFSET_DATE_TIME = 14;
    private static final byte SQL_DATE = 15;
    private static final byte SQL_TIME = 16;
    private static final byte SQL_TIMESTAMP = 17;
    private static final byte BYTES = 18;

    private static final byte ROW = 1;
    private static final byte END = 0;

    private final TempStore store;
    private final SpoolRef ref;
    private final List<String> columns;
    private final long rows;
    private final Map<String, Object> firstRow;

    private SpooledRows(TempStore store, SpoolRef ref, List<String> columns, long rows,
            Map<String, Object> firstRow) {
        this.store = store;
        this.ref = ref;
        this.columns = columns;
        this.rows = rows;
        this.firstRow = firstRow;
    }

    /** Drains {@code source} into a spool; the iterator is exhausted when this returns. */
    public static SpooledRows drain(TempStore store, Iterator<Map<String, Object>> source) {
        SpoolWriter writer = store.createWriter(SpoolKind.BINARY);
        List<String> columns = new ArrayList<>();
        Map<String, Object> firstRow = null;
        long count = 0;
        // The writer takes whole byte arrays, so each row is encoded on its own and handed over:
        // bounded memory, one row at a time, whatever the store does with it.
        try (writer) {
            boolean first = true;
            while (source.hasNext()) {
                Map<String, Object> row = source.next();
                if (first) {
                    columns.addAll(row.keySet());
                    firstRow = row;
                    writer.write(header(columns));
                    first = false;
                } else if (!row.keySet().equals(new java.util.LinkedHashSet<>(columns))) {
                    throw new TqlException(SHAPE_CHANGED, "Row " + (count + 1)
                            + " has columns " + row.keySet() + " where the first row had "
                            + columns + " - a spooled row set is one shape throughout");
                }
                writer.write(row(columns, row));
                writer.incrementRows(1);
                count++;
            }
            if (first) {
                writer.write(header(columns));
            }
            writer.write(new byte[]{END});
        } catch (IOException ex) {
            throw new TqlException(SPOOL_FAILED, "Could not spool rows: " + ex.getMessage());
        }
        return new SpooledRows(store, writer.toRef(), List.copyOf(columns), count, firstRow);
    }

    /**
     * Re-opens rows an earlier {@link #drain} spooled, from the reference it published. This is
     * how a consumer in another step reaches the rows — a batch {@code chunk:} reader loading a
     * {@code query-spool} extract — when all that crossed the step boundary was the
     * {@link SpoolRef}. The header answers {@link #columns()} and the reference carries
     * {@link #size()}; {@link #firstRow()} is {@code null} here, because this shape exists for a
     * consumer that walks the rows, not one that peeks.
     */
    public static SpooledRows open(TempStore store, SpoolRef ref) {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(store.openInput(ref)))) {
            int count = in.readInt();
            List<String> names = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                names.add(in.readUTF());
            }
            return new SpooledRows(store, ref, List.copyOf(names), ref.rows(), null);
        } catch (IOException ex) {
            throw new TqlException(SPOOL_FAILED,
                    "Could not read spooled rows: " + ex.getMessage());
        }
    }

    /**
     * The reference the rows live under — what a step publishes so a later step can
     * {@link #open} the same spool without holding this instance across the boundary.
     */
    public SpoolRef ref() {
        return ref;
    }

    /** The column names, in the order the first row carried them. */
    public List<String> columns() {
        return columns;
    }

    /** How many rows were spooled. */
    public long size() {
        return rows;
    }

    /**
     * The first row, captured while the spool was drained — or {@code null} for an empty one.
     * Answering from here is what keeps a result's {@code first} from opening a reader it would
     * read one row of and abandon: an abandoned reader holds its stream until the walk that
     * never comes, and on a staging {@link TempStore} it strands a full on-disk copy.
     */
    public Map<String, Object> firstRow() {
        return firstRow;
    }

    @Override
    public Iterator<Map<String, Object>> iterator() {
        try {
            DataInputStream in = new DataInputStream(
                    new BufferedInputStream(store.openInput(ref)));
            int count = in.readInt();
            List<String> names = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                names.add(in.readUTF());
            }
            return new SpoolIterator(in, names);
        } catch (IOException ex) {
            throw new TqlException(SPOOL_FAILED, "Could not read spooled rows: " + ex.getMessage());
        }
    }

    /** Deletes the spool. The rows are unreadable afterwards. */
    @Override
    public void close() {
        store.delete(ref);
    }

    private static byte[] header(List<String> columns) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(columns.size());
            for (String column : columns) {
                out.writeUTF(column);
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] row(List<String> columns, Map<String, Object> row) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(ROW);
            for (String column : columns) {
                write(out, column, row.get(column));
            }
        }
        return bytes.toByteArray();
    }

    private static void write(DataOutputStream out, String column, Object value)
            throws IOException {
        switch (value) {
            case null -> out.writeByte(NULL);
            case String text -> {
                out.writeByte(STRING);
                out.writeUTF(text);
            }
            case Boolean flag -> {
                out.writeByte(BOOLEAN);
                out.writeBoolean(flag);
            }
            case Long number -> {
                out.writeByte(LONG);
                out.writeLong(number);
            }
            case Integer number -> {
                out.writeByte(INTEGER);
                out.writeInt(number);
            }
            case Short number -> {
                out.writeByte(SHORT);
                out.writeShort(number);
            }
            case Byte number -> {
                out.writeByte(BYTE);
                out.writeByte(number);
            }
            case Double number -> {
                out.writeByte(DOUBLE);
                out.writeDouble(number);
            }
            case Float number -> {
                out.writeByte(FLOAT);
                out.writeFloat(number);
            }
            // Scale is part of the value for money columns, so the string form is the exact one.
            case BigDecimal number -> {
                out.writeByte(BIG_DECIMAL);
                out.writeUTF(number.toString());
            }
            // java.sql temporals are checked before their java.time supertypes: java.sql.Date
            // extends java.util.Date, and a Timestamp is not a LocalDateTime, so the driver's own
            // type is what comes back.
            case java.sql.Date date -> {
                out.writeByte(SQL_DATE);
                out.writeUTF(date.toString());
            }
            case java.sql.Time time -> {
                out.writeByte(SQL_TIME);
                out.writeUTF(time.toString());
            }
            case java.sql.Timestamp timestamp -> {
                out.writeByte(SQL_TIMESTAMP);
                out.writeLong(timestamp.getTime());
                out.writeInt(timestamp.getNanos());
            }
            case LocalDate date -> {
                out.writeByte(LOCAL_DATE);
                out.writeUTF(date.toString());
            }
            case LocalTime time -> {
                out.writeByte(LOCAL_TIME);
                out.writeUTF(time.toString());
            }
            case LocalDateTime dateTime -> {
                out.writeByte(LOCAL_DATE_TIME);
                out.writeUTF(dateTime.toString());
            }
            case Instant instant -> {
                out.writeByte(INSTANT);
                out.writeUTF(instant.toString());
            }
            case OffsetDateTime dateTime -> {
                out.writeByte(OFFSET_DATE_TIME);
                out.writeUTF(dateTime.toString());
            }
            case byte[] blob -> {
                out.writeByte(BYTES);
                out.writeInt(blob.length);
                out.write(blob);
            }
            default -> throw new TqlException(UNREPRESENTABLE, "Column '" + column + "' holds a "
                    + value.getClass().getName() + ", which the row spool cannot carry without"
                    + " changing it - select it as a supported type in the query");
        }
    }

    /**
     * Reads rows back until the end marker; one open stream per iteration. Walking to the end
     * releases the stream on its own; a consumer that stops early owes a {@link #close()} — the
     * framework's own early-stopper is {@code ExportGroups.KeyedRows}, which closes the reader
     * the moment the ordered rows move past its group.
     */
    private static final class SpoolIterator
            implements
                Iterator<Map<String, Object>>,
                AutoCloseable {

        private final DataInputStream in;
        private final List<String> columns;
        private Map<String, Object> pending;
        private boolean done;

        SpoolIterator(DataInputStream in, List<String> columns) {
            this.in = in;
            this.columns = columns;
        }

        /** Releases the reader without walking the remaining rows; safe to call twice. */
        @Override
        public void close() {
            finish();
        }

        @Override
        public boolean hasNext() {
            if (done) {
                return false;
            }
            if (pending == null) {
                pending = read();
            }
            return pending != null;
        }

        @Override
        public Map<String, Object> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Map<String, Object> row = pending;
            pending = null;
            return row;
        }

        private Map<String, Object> read() {
            try {
                if (in.readByte() == END) {
                    finish();
                    return null;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (String column : columns) {
                    row.put(column, value());
                }
                return row;
            } catch (EOFException ex) {
                // A truncated spool is a broken spool: better a clear failure than a short read
                // silently passing for the end of the data.
                finish();
                throw new TqlException(SPOOL_FAILED,
                        "Spooled rows end before their end marker - the spool is truncated");
            } catch (IOException ex) {
                finish();
                throw new TqlException(SPOOL_FAILED,
                        "Could not read spooled rows: " + ex.getMessage());
            }
        }

        private void finish() {
            if (done) {
                return;
            }
            done = true;
            try {
                in.close();
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }

        private Object value() throws IOException {
            byte tag = in.readByte();
            return switch (tag) {
                case NULL -> null;
                case STRING -> in.readUTF();
                case BOOLEAN -> in.readBoolean();
                case LONG -> in.readLong();
                case INTEGER -> in.readInt();
                case SHORT -> in.readShort();
                case BYTE -> in.readByte();
                case DOUBLE -> in.readDouble();
                case FLOAT -> in.readFloat();
                case BIG_DECIMAL -> new BigDecimal(in.readUTF());
                case LOCAL_DATE -> LocalDate.parse(in.readUTF());
                case LOCAL_TIME -> LocalTime.parse(in.readUTF());
                case LOCAL_DATE_TIME -> LocalDateTime.parse(in.readUTF());
                case INSTANT -> Instant.parse(in.readUTF());
                case OFFSET_DATE_TIME -> OffsetDateTime.parse(in.readUTF());
                case SQL_DATE -> java.sql.Date.valueOf(in.readUTF());
                case SQL_TIME -> java.sql.Time.valueOf(in.readUTF());
                case SQL_TIMESTAMP -> timestamp();
                case BYTES -> in.readNBytes(in.readInt());
                default -> throw new TqlException(SPOOL_FAILED,
                        "Unknown spool type tag " + tag + " - the spool is corrupt");
            };
        }

        private java.sql.Timestamp timestamp() throws IOException {
            java.sql.Timestamp timestamp = new java.sql.Timestamp(in.readLong());
            // setNanos overwrites the whole sub-second part, so it restores what getNanos read.
            timestamp.setNanos(in.readInt());
            return timestamp;
        }
    }
}
