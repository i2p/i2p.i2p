package net.i2p.i2ptunnel.util;

import java.io.OutputStream;

/**
 * Write to nowhere
 *
 * @since 0.9.62 copied from susimail
 */
public class DummyOutputStream extends OutputStream {
    
    public void write(int val) {}

    @Override
    public void write(byte src[]) {}

    @Override
    public void write(byte src[], int off, int len) {}

    @Override
    public void flush() {}

    @Override
    public void close() {}
}
