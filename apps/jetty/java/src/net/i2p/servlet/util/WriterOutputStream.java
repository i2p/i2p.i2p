package net.i2p.servlet.util;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;

/**
 * Treat a writer as an output stream. Quick 'n dirty, none
 * of that "intarnasheeonaleyzayshun" stuff.  So we can treat
 * the jsp's PrintWriter as an OutputStream
 *
 * @since 0.9.33 consolidated from routerconsole and i2psnark
 */
public class WriterOutputStream extends OutputStream {
    private final Writer _writer;
    
    public WriterOutputStream(Writer writer) { _writer = writer; }

    public void write(int b) throws IOException { _writer.write(b); }
}
