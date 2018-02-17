/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.List;

import net.i2p.client.streaming.I2PSocket;

/**
 * Override the response with a stream filtering the HTTP headers
 * received.  Specifically, this makes sure we get Connection: close,
 * so the browser knows they really shouldn't try to use persistent
 * connections.  The HTTP server *should* already be setting this, 
 * since the HTTP headers sent by the browser specify Connection: close,
 * and the server should echo it.  However, both broken and malicious
 * servers could ignore that, potentially confusing the user.
 *
 *  Warning - not maintained as a stable API for external use.
 */
public class I2PTunnelHTTPClientRunner extends I2PTunnelRunner {

    /**
     *  Does NOT start itself. Caller must call start().
     */
    public I2PTunnelHTTPClientRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                                     List<I2PSocket> sockList, FailCallback onFail) {
        super(s, i2ps, slock, initialI2PData, null, sockList, onFail);
    }

    /**
     *  Only call once!
     */
    @Override
    protected OutputStream getSocketOut() throws IOException { 
        OutputStream raw = super.getSocketOut();
        return new HTTPResponseOutputStream(raw);
    }
        
    /**
     *  Why is this overridden?
     *  Why flush in super but not here?
     *  Why do things in different order than in super?
     */
    @Override
    protected void close(OutputStream out, InputStream in, OutputStream i2pout, InputStream i2pin,
                         Socket s, I2PSocket i2ps, Thread t1, Thread t2) throws InterruptedException {
        if (i2pin != null) { try { 
            i2pin.close();
        } catch (IOException ioe) {} }
        if (i2pout != null) { try { 
            i2pout.close();
        } catch (IOException ioe) {} }
        if (in != null) { try { 
            in.close();
        } catch (IOException ioe) {} }
        if (out != null) { try { 
            out.close(); 
        } catch (IOException ioe) {} }
        try { 
            i2ps.close();
        } catch (IOException ioe) {}
        try { 
            s.close();
        } catch (IOException ioe) {}
        if (t1 != null)
            t1.join(30*1000);
        // t2 = fromI2P now run inline
        //t2.join(30*1000);
    }
}
