package net.i2p.router.web;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;

/**
 * Treat a writer as an output stream. Quick 'n dirty, none
 * of that "intarnasheeonaleyzayshun" stuff.  So we can treat
 * the jsp's PrintWriter as an OutputStream
 */
public class WriterOutputStream extends OutputStream {
    private Writer _writer;
    
    public WriterOutputStream(Writer writer) { _writer = writer; }
    public void write(int b) throws IOException { _writer.write(b); }
}
