/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.FilterOutputStream;
import java.net.Socket;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;

/**
 * Override the response with a stream filtering the HTTP headers
 * received.  Specifically, this makes sure we get Connection: close,
 * so the browser knows they really shouldn't try to use persistent
 * connections.  The HTTP server *should* already be setting this, 
 * since the HTTP headers sent by the browser specify Connection: close,
 * and the server should echo it.  However, both broken and malicious
 * servers could ignore that, potentially confusing the user.
 *
 */
public class I2PTunnelHTTPClientRunner extends I2PTunnelRunner {
    public I2PTunnelHTTPClientRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData, List sockList, Runnable onTimeout) {
        super(s, i2ps, slock, initialI2PData, sockList, onTimeout);
    }

    protected OutputStream getSocketOut() throws IOException { 
        OutputStream raw = super.getSocketOut();
        return new HTTPResponseOutputStream(raw);
    }
}
