package i2p.susi.util;

import java.io.OutputStream;

/**
 * Write to nowhere
 *
 * @since 0.9.34
 */
public class DummyOutputStream extends OutputStream {
    
    public DummyOutputStream() {
        super();
    }
    
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
