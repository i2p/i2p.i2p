package net.i2p.router.transport;

import java.io.OutputStream;

/**
 * Output stream for when we don't care whats written
 *
 */
public class NullOutputStream extends OutputStream {
    public void write(int param) {}
}