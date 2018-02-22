package net.i2p.util;

import java.io.IOException;
import java.io.PipedOutputStream;

/**
 *  Helper for TimeoutPipedInputStream.
 *  There isn't any timeout implemented here.
 *
 *  To support InternalSocket.setSoTimeout().
 *  Package private, not a part of the public API, not for general use.
 *
 *  @see TimeoutPipedInputStream
 *  @since 0.9.34
 */
class TimeoutPipedOutputStream extends PipedOutputStream {

    private final TimeoutPipedInputStream sink;

    public TimeoutPipedOutputStream(TimeoutPipedInputStream snk) throws IOException {
        super(snk);
        sink = snk;
    }

    /**
     *  Overridden only so we can tell snk.
     *  We have to do this because TPIS can't get to pkg private receivedLast() in super.
     */
    @Override
    public void close() throws IOException {
        sink.x_receivedLast();
        super.close();
    }
}
