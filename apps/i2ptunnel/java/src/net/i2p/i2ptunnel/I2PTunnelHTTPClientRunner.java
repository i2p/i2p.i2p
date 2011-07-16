/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PSocket;
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
    private Log _log;
    public I2PTunnelHTTPClientRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                                     List<I2PSocket> sockList, Runnable onTimeout) {
        super(s, i2ps, slock, initialI2PData, sockList, onTimeout);
        _log = I2PAppContext.getGlobalContext().logManager().getLog(I2PTunnelHTTPClientRunner.class);
    }

    @Override
    protected OutputStream getSocketOut() throws IOException { 
        OutputStream raw = super.getSocketOut();
        return new HTTPResponseOutputStream(raw);
    }
        
    @Override
    protected void close(OutputStream out, InputStream in, OutputStream i2pout, InputStream i2pin, Socket s, I2PSocket i2ps, Thread t1, Thread t2) throws InterruptedException, IOException {
        try { 
            i2pin.close();
            i2pout.close();
        } catch (IOException ioe) {
            // ignore
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Unable to close the i2p socket output stream: " + i2pout, ioe);
        }
        try { 
            in.close();
            out.close(); 
        } catch (IOException ioe) { 
            // ignore
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Unable to close the browser output stream: " + out, ioe);
        }
        i2ps.close();
        s.close();
        t1.join(30*1000);
        t2.join(30*1000);
    }
    
}
