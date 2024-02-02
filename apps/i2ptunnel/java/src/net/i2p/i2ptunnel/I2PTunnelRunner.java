/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLException;

import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketException;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.i2ptunnel.util.LimitOutputStream.DoneCallback;
import net.i2p.util.ByteCache;
import net.i2p.util.Clock;
import net.i2p.util.I2PAppThread;
import net.i2p.util.InternalSocket;
import net.i2p.util.Log;

/**
 *  A thread that starts one more thread if keepAliveSocket is false,
 *  to forward traffic in each direction.
 *  When keepAliveSocket is true, we do not expect additional data and do not
 *  need a forwarding thread from the socket to I2P.
 *
 *  Warning - not maintained as a stable API for external use.
 */
public class I2PTunnelRunner extends I2PAppThread implements I2PSocket.SocketErrorListener, DoneCallback {
    protected final Log _log;

    private static final AtomicLong __runnerId = new AtomicLong();
    private final long _runnerId;
    /** 
     * max bytes streamed in a packet - smaller ones might be filled
     * up to this size. Larger ones are not split (at least not on
     * Sun's impl of BufferedOutputStream), but that is the streaming
     * api's job...
     */
    static int MAX_PACKET_SIZE = 1024 * 4;

    static final int NETWORK_BUFFER_SIZE = MAX_PACKET_SIZE;

    private final Socket s;
    private final I2PSocket i2ps;
    private final Object slock, finishLock = new Object();
    private volatile boolean finished;
    private final byte[] initialI2PData;
    private final byte[] initialSocketData;
    /** when the runner started up */
    private final long startedOn;
    private final List<I2PSocket> sockList;
    /** if we die before receiving any data, run this job */
    private final Runnable onTimeout;
    private final FailCallback _onFail;
    private SuccessCallback _onSuccess;
    // does not include initialI2PData
    private long totalSent;
    // does not include initialSocketData
    private long totalReceived;
    // not final, may be changed by extending classes
    protected volatile boolean _keepAliveI2P, _keepAliveSocket;
    private StreamForwarder toI2P;
    private StreamForwarder fromI2P;

    /**
     *  For use in new constructor
     *  @since 0.9.14
     */
    public interface FailCallback {
        /**
         *  @param e may be null
         */
        public void onFail(Exception e);
    }

    /**
     *  @since 0.9.39
     */
    public interface SuccessCallback {
        public void onSuccess();
    }

    /**
     *  Starts itself
     *
     *  @param slock the socket lock, non-null
     *  @param initialI2PData may be null
     *  @param sockList may be null. Caller must add i2ps to the list! It will be removed here on completion.
     *                               Will synchronize on slock when removing.
     *  @deprecated use FailCallback constructor
     */
    @Deprecated
    public I2PTunnelRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                           List<I2PSocket> sockList) {
        this(s, i2ps, slock, initialI2PData, null, sockList, null, null, true);
    }

    /**
     *  Starts itself
     *
     *  @param slock the socket lock, non-null
     *  @param initialI2PData may be null
     *  @param initialSocketData may be null
     *  @param sockList may be null. Caller must add i2ps to the list! It will be removed here on completion.
     *                               Will synchronize on slock when removing.
     *  @deprecated use FailCallback constructor
     */
    @Deprecated
    public I2PTunnelRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                           byte[] initialSocketData, List<I2PSocket> sockList) {
        this(s, i2ps, slock, initialI2PData, initialSocketData, sockList, null, null, true);
    }

    /**
     *  Starts itself
     *
     *  @param slock the socket lock, non-null
     *  @param initialI2PData may be null
     *  @param sockList may be null. Caller must add i2ps to the list! It will be removed here on completion.
     *                               Will synchronize on slock when removing.
     *  @param onTimeout May be null. If non-null and no data (except initial data) was received,
     *                   it will be run before closing s.
     *  @deprecated use FailCallback constructor
     */
    @Deprecated
    public I2PTunnelRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                           List<I2PSocket> sockList, Runnable onTimeout) {
        this(s, i2ps, slock, initialI2PData, null, sockList, onTimeout, null, true);
    }

    /**
     *  Starts itself
     *
     *  @param slock the socket lock, non-null
     *  @param initialI2PData may be null
     *  @param initialSocketData may be null
     *  @param sockList may be null. Caller must add i2ps to the list! It will be removed here on completion.
     *                               Will synchronize on slock when removing.
     *  @param onTimeout May be null. If non-null and no data (except initial data) was received,
     *                   it will be run before closing s.
     *  @deprecated use FailCallback constructor
     */
    @Deprecated
    public I2PTunnelRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                           byte[] initialSocketData, List<I2PSocket> sockList, Runnable onTimeout) {
        this(s, i2ps, slock, initialI2PData, initialSocketData, sockList, onTimeout, null, true);
    }

    /**
     *  Recommended new constructor. Does NOT start itself. Caller must call start().
     *
     *  @param slock the socket lock, non-null
     *  @param initialI2PData may be null
     *  @param initialSocketData may be null
     *  @param sockList may be null. Caller must add i2ps to the list! It will be removed here on completion.
     *                               Will synchronize on slock when removing.
     *  @param onFail May be null. If non-null and no data (except initial data) was received,
     *                it will be run before closing s.
     */
    public I2PTunnelRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                           byte[] initialSocketData, List<I2PSocket> sockList, FailCallback onFail) {
        this(s, i2ps, slock, initialI2PData, initialSocketData, sockList, null, onFail, false);
    }

    /**
     *  With keepAlive args. Does NOT start itself. Caller must call start().
     *
     *  @param slock the socket lock, non-null
     *  @param initialI2PData may be null
     *  @param initialSocketData may be null
     *  @param sockList may be null. Caller must add i2ps to the list! It will be removed here on completion.
     *                               Will synchronize on slock when removing.
     *  @param onFail May be null. If non-null and no data (except initial data) was received,
     *                it will be run before closing s.
     *  @param keepAliveI2P Do not close the I2P socket when done.
     *  @param keepAliveSocket Do not close the local socket when done.
     *                         For client side only; must be false for server side.
     *                         NO data will be forwarded from the socket to the i2psocket other than
     *                         initialI2PData if this is true.
     *  @since 0.9.62
     */
    public I2PTunnelRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                           byte[] initialSocketData, List<I2PSocket> sockList, FailCallback onFail,
                           boolean keepAliveI2P, boolean keepAliveSocket) {
        this(s, i2ps, slock, initialI2PData, initialSocketData, sockList, null, onFail, keepAliveI2P, keepAliveSocket, false);
    }

    /**
     *  Base constructor
     *
     *  @param slock the socket lock, non-null
     *  @param initialI2PData may be null
     *  @param initialSocketData may be null
     *  @param sockList may be null. Caller must add i2ps to the list! It will be removed here on completion.
     *                               Will synchronize on slock when removing.
     *  @param onTimeout May be null. If non-null and no data (except initial data) was received,
     *                   it will be run before closing s.
     *  @param onFail Trumps onTimeout
     *  @param shouldStart should thread be started in constructor (bad, false recommended)
     */
    private I2PTunnelRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                            byte[] initialSocketData, List<I2PSocket> sockList, Runnable onTimeout,
                            FailCallback onFail, boolean shouldStart) {
        this(s, i2ps, slock, initialI2PData, initialSocketData, sockList, null, onFail, false, false, shouldStart);
    }

    /**
     *  Base constructor with keepAlive args
     *
     *  @param slock the socket lock, non-null
     *  @param initialI2PData may be null
     *  @param initialSocketData may be null
     *  @param sockList may be null. Caller must add i2ps to the list! It will be removed here on completion.
     *                               Will synchronize on slock when removing.
     *  @param onTimeout May be null. If non-null and no data (except initial data) was received,
     *                   it will be run before closing s.
     *  @param onFail Trumps onTimeout
     *  @param shouldStart should thread be started in constructor (bad, false recommended)
     *  @param keepAliveI2P Do not close the I2P socket when done.
     *  @param keepAliveSocket Do not close the local socket when done.
     *                         For client side only; must be false for server side.
     *                         NO data will be forwarded from the socket to the i2psocket other than
     *                         initialI2PData if this is true.
     *  @since 0.9.62
     */
    private I2PTunnelRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialI2PData,
                            byte[] initialSocketData, List<I2PSocket> sockList, Runnable onTimeout,
                            FailCallback onFail,
                            boolean keepAliveI2P, boolean keepAliveSocket,
                            boolean shouldStart) {
        this.sockList = sockList;
        this.s = s;
        this.i2ps = i2ps;
        this.slock = slock;
        this.initialI2PData = initialI2PData;
        this.initialSocketData = initialSocketData;
        this.onTimeout = onTimeout;
        _onFail = onFail;
        startedOn = Clock.getInstance().now();
        _log = I2PAppContext.getGlobalContext().logManager().getLog(getClass());
        _keepAliveI2P = keepAliveI2P;
        _keepAliveSocket = keepAliveSocket;
        if (_log.shouldLog(Log.INFO))
            _log.info("I2PTunnelRunner started");
        _runnerId = __runnerId.incrementAndGet();
        if (shouldStart) {
            setName("I2PTunnelRunner " + _runnerId);
            start();
        }
    }

    /** 
     * have we closed at least one (if not both) of the streams 
     * [aka we're done running the streams]? 
     *
     * @deprecated unused
     */
    @Deprecated
    public boolean isFinished() {
        return finished;
    }

    /** 
     * When was the last data for this runner sent or received?  
     * As of 0.9.20, returns -1 always!
     *
     * @return date (ms since the epoch), or -1 if no data has been transferred yet
     * @deprecated unused
     */
    @Deprecated
    public long getLastActivityOn() {
        return -1L;
    }

    /**
     * When this runner started up transferring data
     *
     */
    public long getStartedOn() {
        return startedOn;
    }

    /**
     * Will be called if we get any data back.
     * This is called after the first byte of data is received, not on completion.
     * Only one of SuccessCallback, onTimeout, or onFail will be called.
     *
     * @since 0.9.39
     */
    public void setSuccessCallback(SuccessCallback sc) {
        _onSuccess = sc;
    }

    protected InputStream getSocketIn() throws IOException { return s.getInputStream(); }
    protected OutputStream getSocketOut() throws IOException { return s.getOutputStream(); }

    /**
     * Should we keep the I2P socket open when done?
     * On the client side, only true if the browser and the server side support it.
     * On the server side, only true if the client supports it.
     * @since 0.9.62
     */
    boolean getKeepAliveI2P() {
        return _keepAliveI2P;
    }

    /**
     * Should we keep the local browser/server socket open when done?
     * Usually true for client side.
     * Always false for server side.
     * @since 0.9.62
     */
    boolean getKeepAliveSocket() {
        return _keepAliveSocket;
    }

    /**
     * The DoneCallback for the I2P socket.
     *
     * @since 0.9.62
     */
    public void streamDone() {
        if (_keepAliveSocket && fromI2P != null) {
            // we are client-side
            // tell the from-I2P runner
            if (_log.shouldInfo())
                _log.info("Stream done from I2P", new Exception("I did it"));
            fromI2P.done = true;
        } else if (_keepAliveI2P && toI2P != null) {
            // we are server-side
            // tell the to-I2P runner
            if (_log.shouldInfo())
                _log.info("Stream done from Server", new Exception("I did it"));
            toI2P.done = true;
        } else {
            if (_log.shouldWarn())
                _log.info("Unexpected stream done", new Exception("I did it"));
        }
    }
    
    private static final byte[] POST = { 'P', 'O', 'S', 'T', ' ' };
    private static final byte[] PUT = { 'P', 'U', 'T', ' ' };

    @Override
    public void run() {
        boolean i2pReset = false;
        boolean sockReset = false;
        InputStream in = null;
        OutputStream out = null;
        InputStream i2pin = null;
        OutputStream i2pout = null;
        try {
            out = getSocketOut(); // = new BufferedOutputStream(s.getOutputStream(), NETWORK_BUFFER_SIZE);
            // unimplemented in streaming
            //i2ps.setSocketErrorListener(this);
            i2pin = i2ps.getInputStream();
            i2pout = i2ps.getOutputStream(); //new BufferedOutputStream(i2ps.getOutputStream(), MAX_PACKET_SIZE);
            if (initialI2PData != null) {
                // why synchronize this? we could be in here a LONG time for large initial data
                //synchronized (slock) {
                    // this does not increment totalSent
                    i2pout.write(initialI2PData);
                    // do NOT flush here, it will block and then onTimeout.run() won't happen on fail.
                    // But if we don't flush, then we have to wait for the connectDelay timer to fire
                    // in i2p socket? To be researched and/or fixed.
                    //
                    // AS OF 0.8.1, MessageOutputStream.flush() is fixed to only wait for accept,
                    // not for "completion" (i.e. an ACK from the far end).
                    // So we now get a fast return from flush(), and can do it here to save 250 ms.
                    // To make sure we are under the initial window size and don't hang waiting for accept,
                    // only flush if it fits in one message.
                    if (initialI2PData.length <= 1730) {  // ConnectionOptions.DEFAULT_MAX_MESSAGE_SIZE
                        // Don't flush if POST, so we can get POST data into the initial packet
                        if (initialI2PData.length < 5 ||
                            !(DataHelper.eq(POST, 0, initialI2PData, 0, 5) ||
                              DataHelper.eq(PUT, 0, initialI2PData, 0, 4)))
                            i2pout.flush();
                    }
                //}
            }
            if (initialSocketData != null) {
                // this does not increment totalReceived
                out.write(initialSocketData);
            }
            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug("Initial data " + (initialI2PData != null ? initialI2PData.length : 0) 
                           + " written to I2P, " + (initialSocketData != null ? initialSocketData.length : 0)
                           + " written to the socket, starting forwarders");

            }
            if (_keepAliveSocket) {
                // standard GET or HEAD, no data, do not thread a forwarder
                // because we don't need it and
                // we don't want it to swallow the next request
            } else {
                in = getSocketIn();
                // InternalSocket already has buffering
                if (!(s instanceof InternalSocket))
                    in = new BufferedInputStream(in, 2*NETWORK_BUFFER_SIZE);
                toI2P = new StreamForwarder(in, i2pout, true, null);
                toI2P.start();
            }
            fromI2P = new StreamForwarder(i2pin, out, false, _onSuccess);
            // We are already a thread, so run the second one inline
            //fromI2P.start();
            fromI2P.run();
            synchronized (finishLock) {
                while (!finished) {
                    finishLock.wait();
                }
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Both forwarders completed, sent: " + totalSent + " received: " + totalReceived);
            
            // this task is useful for the httpclient
            if ((onTimeout != null || _onFail != null) && totalReceived <= 0) {
                //if (_log.shouldLog(Log.DEBUG))
                //     _log.debug("runner has a timeout job, totalReceived = " + totalReceived
                //                + " totalSent = " + totalSent + " job = " + onTimeout);
                // Run even if totalSent > 0, as that's probably POST data.
                // This will be run even if initialSocketData != null, it's the timeout job's
                // responsibility to know that and decide whether or not to write to the socket.
                // HTTPClient never sets initialSocketData.
                if (_onFail != null) {
                    Exception e = fromI2P.getFailure();
                    if (e == null && toI2P != null)
                        e = toI2P.getFailure();
                    _onFail.onFail(e);
                } else {
                    onTimeout.run();
                }
            } else {
                // Detect a reset on one side, and propagate to the other
                Exception e1 = fromI2P.getFailure();
                Exception e2 = toI2P != null ? toI2P.getFailure() : null;
                Throwable c1 = e1 != null ? e1.getCause() : null;
                Throwable c2 = e2 != null ? e2.getCause() : null;
                if (c1 != null && c1 instanceof I2PSocketException) {
                    I2PSocketException ise = (I2PSocketException) c1;
                    int status = ise.getStatus();
                    i2pReset = status == I2PSocketException.STATUS_CONNECTION_RESET;
                }
                if (!i2pReset && c2 != null && c2 instanceof I2PSocketException) {
                    I2PSocketException ise = (I2PSocketException) c2;
                    int status = ise.getStatus();
                    i2pReset = status == I2PSocketException.STATUS_CONNECTION_RESET;
                }
                if (!i2pReset && e1 != null && e1 instanceof SocketException) {
                        String msg = e1.getMessage();
                        sockReset = msg != null && msg.contains("reset");
                }
                if (!sockReset && e2 != null && e2 instanceof SocketException) {
                        String msg = e2.getMessage();
                        sockReset = msg != null && msg.contains("reset");
                }
            }

        } catch (InterruptedException ex) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Interrupted", ex);
            _keepAliveI2P = false;
            _keepAliveSocket = false;
        } catch (SSLException she) {
            _log.error("SSL error", she);
            _keepAliveI2P = false;
            _keepAliveSocket = false;
        } catch (IOException ex) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Error forwarding", ex);
            _keepAliveI2P = false;
            _keepAliveSocket = false;
        } catch (IllegalStateException ise) {
            // JamVM (Gentoo: jamvm-1.5.4, gnu-classpath-0.98+gmp)
		//java.nio.channels.NotYetConnectedException
		//   at gnu.java.nio.SocketChannelImpl.write(SocketChannelImpl.java:240)
		//   at gnu.java.net.PlainSocketImpl$SocketOutputStream.write(PlainSocketImpl.java:668)
		//   at java.io.OutputStream.write(OutputStream.java:86)
		//   at net.i2p.i2ptunnel.I2PTunnelHTTPClient.writeFooter(I2PTunnelHTTPClient.java:1029)
		//   at net.i2p.i2ptunnel.I2PTunnelHTTPClient.writeErrorMessage(I2PTunnelHTTPClient.java:1114)
		//   at net.i2p.i2ptunnel.I2PTunnelHTTPClient.handleHTTPClientException(I2PTunnelHTTPClient.java:1131)
		//   at net.i2p.i2ptunnel.I2PTunnelHTTPClient.access$000(I2PTunnelHTTPClient.java:67)
		//   at net.i2p.i2ptunnel.I2PTunnelHTTPClient$OnTimeout.run(I2PTunnelHTTPClient.java:1052)
		//   at net.i2p.i2ptunnel.I2PTunnelRunner.run(I2PTunnelRunner.java:167)
            if (_log.shouldLog(Log.WARN))
                _log.warn("gnu?", ise);
            _keepAliveI2P = false;
            _keepAliveSocket = false;
        } catch (RuntimeException e) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Internal error", e);
            _keepAliveI2P = false;
            _keepAliveSocket = false;
        } finally {
            removeRef();
            if (i2pReset) {
                if (_log.shouldWarn())
                    _log.warn("Got I2P reset, resetting socket");
                try { 
                    s.setSoLinger(true, 0);
                } catch (IOException ioe) {}
                try { 
                    s.close();
                } catch (IOException ioe) {}
                try { 
                    i2ps.close();
                } catch (IOException ioe) {}
                _keepAliveI2P = false;
                _keepAliveSocket = false;
            } else if (sockReset) {
                if (_log.shouldWarn())
                    _log.warn("Got socket reset, resetting I2P socket");
                try { 
                    i2ps.reset();
                } catch (IOException ioe) {}
                try { 
                    s.close();
                } catch (IOException ioe) {}
                _keepAliveI2P = false;
                _keepAliveSocket = false;
            } else {
                // now one connection is dead - kill the other as well, after making sure we flush
                try {
                    close(out, in, i2pout, i2pin, s, i2ps, toI2P, fromI2P);
                } catch (InterruptedException ie) {}
            }
        }
    }

    /**
     *  Warning - overridden in I2PTunnelHTTPClientRunner.
     *  Here we ignore keepalive and always close both sides.
     *  The HTTP flavor handles keepalive.
     *
     *  @param out may be null
     *  @param in may be null
     *  @param i2pout may be null
     *  @param i2pin may be null
     *  @param t1 may be null
     *  @param t2 may be null, ignored, we only join t1
     */
    protected void close(OutputStream out, InputStream in, OutputStream i2pout, InputStream i2pin,
                         Socket s, I2PSocket i2ps, Thread t1, Thread t2) throws InterruptedException {
        if (out != null) { try { 
            out.flush(); 
        } catch (IOException ioe) {} }
        if (i2pout != null) { try { 
            i2pout.flush();
        } catch (IOException ioe) {} }
        if (in != null) { try { 
            in.close();
        } catch (IOException ioe) {} }
        if (i2pin != null) { try { 
            i2pin.close();
        } catch (IOException ioe) {} }
        // ok, yeah, there's a race here in theory, if data comes in after flushing and before
        // closing, but its better than before...
        try { 
            s.close();
        } catch (IOException ioe) {}
        try { 
            i2ps.close();
        } catch (IOException ioe) {}
        if (t1 != null)
            t1.join(30*1000);
    }
    
    /**
     * Deprecated, unimplemented in streaming, never called.
     * @deprecated unused
     */
    @Deprecated
    public void errorOccurred() {
        synchronized (finishLock) {
            finished = true;
            finishLock.notifyAll();
        }
    }

    private void removeRef() {
        if (sockList != null) {
            synchronized (slock) {
                sockList.remove(i2ps);
            }
        }
    }

    /**
     *  Forward data in one direction
     */
    private class StreamForwarder extends I2PAppThread {

        private final InputStream in;
        private final OutputStream out;
        private final String direction;
        private final boolean _toI2P;
        private final ByteCache _cache;
        private final SuccessCallback _callback;
        private volatile Exception _failure;
        // does not need to be volatile, will be set from same thread
        public boolean done;

        /**
         *  Does not start itself. Caller must start()
         *  @param cb may be null, only used for toI2P == false
         */
        public StreamForwarder(InputStream in, OutputStream out, boolean toI2P, SuccessCallback cb) {
            this.in = in;
            this.out = out;
            _toI2P = toI2P;
            _callback = cb;
            direction = (toI2P ? "toI2P" : "fromI2P");
            _cache = ByteCache.getInstance(32, NETWORK_BUFFER_SIZE);
            if (toI2P)
                setName("StreamForwarder " + _runnerId + '.' + direction);
        }

        @Override
        public void run() {
            String from = i2ps.getThisDestination().calculateHash().toBase64().substring(0,6);
            String to = i2ps.getPeerDestination().calculateHash().toBase64().substring(0,6);

            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug(direction + ": Forwarding between " 
                           + from + " and " + to);
            }
            
            ByteArray ba = _cache.acquire();
            byte[] buffer = ba.getData();
            try {
                int len;
                while (!done && (len = in.read(buffer)) != -1) {
                    if (len > 0) {
                        out.write(buffer, 0, len);
                        if (_toI2P) {
                            totalSent += len;
                        } else {
                            if (totalReceived == 0 && _callback != null)
                                _callback.onSuccess();
                            totalReceived += len;
                        }
                        //updateActivity();
                    }

                    if (in.available() == 0) {
                        //if (_log.shouldLog(Log.DEBUG))
                        //    _log.debug("Flushing after sending " + len + " bytes through");
                        //if (_log.shouldLog(Log.DEBUG))
                        //    _log.debug(direction + ": " + len + " bytes flushed through " + (_toI2P ? "to " : "from ")
                        //               + to);
                        if (_toI2P) {
                            try {
                                Thread.sleep(5);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            
                            if (in.available() <= 0)
                                out.flush();
                        } else {
                            out.flush();
                        }
                    }
                }
                //if (_log.shouldDebug())
                //    _log.debug(direction + ": Normal EOF on read");
                //out.flush(); // close() flushes
            } catch (SocketException ex) {
                // this *will* occur when the other threads closes the socket
                if (_log.shouldDebug()) {
                    boolean fnshd;
                    synchronized (finishLock) {
                        fnshd = finished;
                    }
                    if (!fnshd) {
                        _log.debug(direction + ": IOE - error forwarding", ex);
                    } else {
                        _log.debug(direction + ": IOE caused by other direction", ex);
                    }
                }
                _failure = ex;
            } catch (IOException ex) {
                if (_log.shouldWarn()) {
                    boolean fnshd;
                    synchronized (finishLock) {
                        fnshd = finished;
                    }
                    if (!fnshd) {
                        _log.warn(direction + ": IOE - error forwarding", ex);
                    } else if (_log.shouldDebug()) {
                        _log.debug(direction + ": IOE caused by other direction", ex);
                    }
                }
                _failure = ex;
            } finally {
                _cache.release(ba);
                boolean keepAliveFrom, keepAliveTo;
                if (_toI2P) {
                    keepAliveFrom = _keepAliveSocket;
                    keepAliveTo = _keepAliveI2P;
                } else {
                    keepAliveFrom = _keepAliveI2P;
                    keepAliveTo = _keepAliveSocket;
                }
                if (_log.shouldLog(Log.INFO)) {
                    _log.info(direction + ": done forwarding from " 
                              + from + " to " + to
                              + " keepalive from? " + keepAliveFrom
                              + " keepalive to? " + keepAliveTo
                              + " bytes: " + (_toI2P ? totalSent : totalReceived));
                }
                if (!keepAliveFrom) {
                    try {
                        in.close();
                    } catch (IOException ex) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn(direction + ": Error closing input stream", ex);
                    }
                }
                try {
                    // Thread must close() before exiting for a PipedOutputStream,
                    // or else input end gives up and we have data loss.
                    // http://techtavern.wordpress.com/2008/07/16/whats-this-ioexception-write-end-dead/
                    //out.flush();
                    // DON'T close if we have a timeout job and we haven't received anything,
                    // or else the timeout job can't write the error message to the stream.
                    // close() above will close it after the timeout job is run.
                    if (!((onTimeout != null || _onFail != null) && (!_toI2P) && totalReceived <= 0)) {
                        if (keepAliveTo)
                            out.flush();
                        else
                            out.close();
                    } else {
                        if (_log.shouldInfo())
                            _log.info(direction + ": not closing so we can write the error message");
                        if (keepAliveTo)
                            out.flush();
                    }
                } catch (IOException ioe) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug(direction + ": Error flushing to close", ioe);
                }
                synchronized (finishLock) {
                    finished = true;
                    finishLock.notifyAll();
                    // the main thread will close sockets etc. now
                }
            }
        }

        /**
         *  @since 0.9.14
         */
        public Exception getFailure() {
            return _failure;
        }
    }
}
