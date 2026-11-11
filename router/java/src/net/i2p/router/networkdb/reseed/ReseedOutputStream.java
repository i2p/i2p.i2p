package net.i2p.router.networkdb.reseed;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Bounds the decoded response, including HTTPS and gzip expansion. */
final class ReseedOutputStream extends FilterOutputStream {
    private final long _limit;
    private long _written;

    ReseedOutputStream(OutputStream out, long limit) {
        super(out);
        _limit = limit;
    }

    @Override
    public void write(int value) throws IOException {
        checkSize(1);
        out.write(value);
        _written++;
    }

    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > data.length - length)
            throw new IndexOutOfBoundsException();
        checkSize(length);
        out.write(data, offset, length);
        _written += length;
    }

    private void checkSize(int length) throws IOException {
        if (length > _limit - _written)
            throw new IOException("Reseed response exceeds " + _limit + " bytes");
    }
}
