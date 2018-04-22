package i2p.susi.util;

import java.io.Writer;

/**
 * StringBuilder version of StringWriter
 *
 * @since 0.9.35
 */
public class StringBuilderWriter extends Writer {
    
    private final StringBuilder buf;

    public StringBuilderWriter() { this(128); }

    public StringBuilderWriter(int capacity) {
        super();
        buf = new StringBuilder(capacity);
    }
    
    @Override
    public Writer append(char c) {
        buf.append(c);
        return this;
    }
    
    @Override
    public Writer append(CharSequence str) {
        buf.append(str);
        return this;
    }
    
    @Override
    public Writer append(CharSequence str, int off, int len) {
        buf.append(str, off, len);
        return this;
    }

    @Override
    public void write(char[] cbuf) {
        buf.append(cbuf);
    }

    public void write(char[] cbuf, int off, int len) {
        buf.append(cbuf, off, len);
    }

    @Override
    public void write(int c) {
        buf.append(c);
    }

    @Override
    public void write(String str) {
        buf.append(str);
    }

    @Override
    public void write(String str, int off, int len) {
        buf.append(str, off, len);
    }

    /**
     *  Does nothing.
     */
    public void close() {}

    /**
     *  Does nothing.
     */
    public void flush() {}

    public StringBuilder getBuilder() { return buf; }

    @Override
    public String toString() { return buf.toString(); }
}
