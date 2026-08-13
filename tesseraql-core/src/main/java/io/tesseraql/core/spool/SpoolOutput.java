package io.tesseraql.core.spool;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * A {@link SpoolWriter} as the {@link OutputStream} a codec writes to. Both export surfaces
 * carried a private copy of this adapter; the byte-array copy per chunk is the writer's
 * whole-array contract, not an accident.
 */
public final class SpoolOutput extends OutputStream {

    private final SpoolWriter writer;

    public SpoolOutput(SpoolWriter writer) {
        this.writer = writer;
    }

    @Override
    public void write(int b) throws IOException {
        writer.write(new byte[]{(byte) b});
    }

    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
        writer.write(Arrays.copyOfRange(data, offset, offset + length));
    }
}
