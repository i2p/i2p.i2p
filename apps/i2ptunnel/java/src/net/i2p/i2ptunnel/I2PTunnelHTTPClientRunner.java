/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.util.I2PAppThread;

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
    private HTTPResponseOutputStream _hout;
    private final boolean _isHead;

    /**
     *  Does NOT start itself. Caller must call start().
     *
     *  @deprecated use other constructor
     */
    @Deprecated
    public I2PTunnelHTTPClientRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                                     List<I2PSocket> sockList, FailCallback onFail) {
        super(s, i2ps, slock, initialI2PData, null, sockList, onFail);
        _isHead = false;
    }

    /**
     *  Does NOT start itself. Caller must call start().
     *
     *  @param allowKeepAliveI2P we may, but are not required to, keep the I2P socket alive
     *                           - Requires allowKeepAliveSocket
     *  @param allowKeepAliveSocket we may, but are not required to, keep the browser-side socket alive
     *                              NO data will be forwarded from the socket to the i2psocket other than
     *                              initialI2PData if this is true.
     *  @param isHead is this a response to a HEAD, and thus no data is expected (RFC 2616 sec. 4.4)
     *  @since 0.9.62
     */
    public I2PTunnelHTTPClientRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                                     List<I2PSocket> sockList, FailCallback onFail,
                                     boolean allowKeepAliveI2P,
                                     boolean allowKeepAliveSocket, boolean isHead) {
        super(s, i2ps, slock, initialI2PData, null, sockList, onFail, allowKeepAliveI2P, allowKeepAliveSocket);
        if (allowKeepAliveI2P && !allowKeepAliveSocket)
            throw new IllegalArgumentException();
        _isHead = isHead;
    }

    /**
     *  Only call once!
     *
     *  @return an HTTPResponseOutputStream
     *  @throws IllegalStateException if called again
     */
    @Override
    protected OutputStream getSocketOut() throws IOException { 
        if (_hout != null)
            throw new IllegalStateException("already called");
        OutputStream raw = super.getSocketOut();
        _hout = new HTTPResponseOutputStream(raw, super.getKeepAliveI2P(), super.getKeepAliveSocket(), _isHead, this);
        return _hout;
    }

    /**
     * Should we keep the local browser socket open when done?
     * @since 0.9.62
     */
    @Override
    boolean getKeepAliveSocket() {
        return _hout != null && _hout.getKeepAliveOut() && super.getKeepAliveSocket();
    }
        
    /**
     * Should we keep the I2P socket open when done?
     * @since 0.9.62
     */
    @Override
    boolean getKeepAliveI2P() {
        return _hout != null && _hout.getKeepAliveIn() && super.getKeepAliveI2P();
    }
        
    /**
     *  May not actually close either socket, depending on keepalive settings.
     *
     *  @param out may be null
     *  @param in may be null
     *  @param i2pout may be null
     *  @param i2pin may be null
     *  @param s non-null
     *  @param i2ps non-null
     *  @param t1 may be null
     *  @param t2 may be null, ignored, we only join t1
     */
    @Override
    protected void close(OutputStream out, InputStream in, OutputStream i2pout, InputStream i2pin,
                         Socket s, I2PSocket i2ps, Thread t1, Thread t2) throws InterruptedException {
        boolean keepaliveSocket = getKeepAliveSocket();
        boolean keepaliveI2P = getKeepAliveI2P();
        boolean threadI2PClose = keepaliveSocket && !keepaliveI2P && i2pout != null && !i2ps.isClosed();
        if (_log.shouldInfo())
            _log.info("Closing HTTPClientRunner keepaliveI2P? " + keepaliveI2P + " keepaliveSocket? " + keepaliveSocket +
                      " threadedClose? " + threadI2PClose, new Exception("I did it"));
        if (threadI2PClose) {
            // Thread the I2P stream/socket closing, because it is blocking, may take several seconds,
            // and we don't want to delay the next request
            Thread t = new I2PSocketCloser(i2pin, i2pout, i2ps);
            TunnelControllerGroup tcg = TunnelControllerGroup.getInstance();
            if (tcg != null) {
                try {
                    tcg.getClientExecutor().execute(t);
                } catch (RejectedExecutionException ree) {}
            } else {
                t.start();
            }
        } else {
            if (!keepaliveI2P) {
                if (i2pin != null) { try { 
                    i2pin.close();
                } catch (IOException ioe) {} }
            }
            if (i2pout != null) { try { 
                if (keepaliveI2P)
                    i2pout.flush(); 
                else
                    i2pout.close(); 
            } catch (IOException ioe) {} }
        }

        if (!keepaliveSocket) {
            if (in != null) { try { 
                in.close();
            } catch (IOException ioe) {} }
        }

        if (out != null) { try { 
            if (keepaliveSocket)
                out.flush(); 
            else
                out.close(); 
        } catch (IOException ioe) {} }

        if (!threadI2PClose && !keepaliveI2P) {
            try { 
                i2ps.close();
            } catch (IOException ioe) {}
        }

        if (!keepaliveSocket) {
            try { 
                s.close();
            } catch (IOException ioe) {}
        }
        if (t1 != null)
            t1.join(30*1000);
    }

    /**
     *  Thread the I2P socket close, so we don't hold up
     *  the next request if the browser socket is keepalive.
     *
     *  @since 0.9.xx
     */
    private class I2PSocketCloser extends I2PAppThread {
        private final InputStream in;
        private final OutputStream out;
        private final I2PSocket s;

        /**
         *  @param in may be null
         *  @param out non-null
         *  @param i2ps non-null
         */
        public I2PSocketCloser(InputStream i2pin, OutputStream i2pout, I2PSocket i2ps) {
            in = i2pin;
            out = i2pout;
            s = i2ps;
        }

        @Override
        public void run() {
            if (in != null) {
                try { 
                    in.close();
                } catch (IOException ioe) {}
            }
            try { 
                out.close(); 
            } catch (IOException ioe) {}
            try { 
                s.close();
            } catch (IOException ioe) {}
            //_log.info("(threaded) i2p socket closed");
        }
    }
}
