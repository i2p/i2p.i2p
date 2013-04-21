package org.klomp.snark.web;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;

/**
 * Treat a writer as an output stream. Quick 'n dirty, none
 * of that "intarnasheeonaleyzayshun" stuff.  So we can treat
 * the jsp's PrintWriter as an OutputStream
 *
 * @since Jetty 7 copied from routerconsole
 */
class WriterOutputStream extends OutputStream {
    private final Writer _writer;
    
    public WriterOutputStream(Writer writer) { _writer = writer; }
    public void write(int b) throws IOException { _writer.write(b); }
}
