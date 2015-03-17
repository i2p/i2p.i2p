package net.i2p.data.i2np;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;

import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * The I2NPMessageReader reads an InputStream (using
 * {@link I2NPMessageHandler I2NPMessageHandler}) and passes out events to a registered
 * listener, where events are either messages being received, exceptions being
 * thrown, or the connection being closed.  Routers should use this rather
 * than read from the stream themselves.
 *
 * Deprecated - unused.
 * This was used by the old TCP transport.
 * Both the NTCP and SSU transports provide encapsulation
 * of I2NP messages, so they use I2NPMessageHandlers directly.
 * If we ever add a transport that does not provide encapsulation,
 * this will be useful again.
 *
 * @deprecated unused
 *
 * @author jrandom
 */
public class I2NPMessageReader {
    private Log _log;
    private RouterContext _context;
    private InputStream _stream;
    private I2NPMessageEventListener _listener;
    private I2NPMessageReaderRunner _reader;
    private Thread _readerThread;
    
    public I2NPMessageReader(RouterContext context, InputStream stream, I2NPMessageEventListener lsnr) {
        this(context, stream, lsnr, "I2NP Reader");
    }
    
    public I2NPMessageReader(RouterContext context, InputStream stream, I2NPMessageEventListener lsnr, String name) {
        _context = context;
        _log = context.logManager().getLog(I2NPMessageReader.class);
        _stream = stream;
        setListener(lsnr);
        _reader = new I2NPMessageReaderRunner();
        _readerThread = new I2PThread(_reader);
        _readerThread.setName(name);
        _readerThread.setDaemon(true);
    }
    
    public void setListener(I2NPMessageEventListener lsnr) { _listener = lsnr; }
    public I2NPMessageEventListener getListener() { return _listener; }
    
    /**
     * Instruct the reader to begin reading messages off the stream
     *
     */
    public void startReading() { _readerThread.start(); }

    /**
     * Have the already started reader pause its reading indefinitely
     * @deprecated unused
     */
    public void pauseReading() { _reader.pauseRunner(); }

    /**
     * Resume reading after a pause
     * @deprecated unused
     */
    public void resumeReading() { _reader.resumeRunner(); }

    /**
     * Cancel reading.
     *
     */
    public void stopReading() { _reader.cancelRunner(); }
    
    /**
     * Defines the different events the reader produces while reading the stream
     *
     */
    public static interface I2NPMessageEventListener {
        /**
         * Notify the listener that a message has been received from the given
         * reader
         *
         */
        public void messageReceived(I2NPMessageReader reader, I2NPMessage message, long msToRead, int bytesRead);
        /**
         * Notify the listener that an exception was thrown while reading from the given
         * reader
         *
         */
        public void readError(I2NPMessageReader reader, Exception error);
        /**
         * Notify the listener that the stream the given reader was running off
         * closed
         *
         */
        public void disconnected(I2NPMessageReader reader);
    }
    
    private class I2NPMessageReaderRunner implements Runnable {
        private boolean _doRun;
        private boolean _stayAlive;
        private I2NPMessageHandler _handler;
        public I2NPMessageReaderRunner() {
            _doRun = true;
            _stayAlive = true;
            _handler = new I2NPMessageHandler(_context);
        }

        /** deprecated unused */
        public void pauseRunner() { _doRun = false; }

        /** deprecated unused */
        public void resumeRunner() { _doRun = true; }

        public void cancelRunner() {
            _doRun = false;
            _stayAlive = false;
        }
        public void run() {
            while (_stayAlive) {
                while (_doRun) {
                    while (!_context.throttle().acceptNetworkMessage()) {
                        try { Thread.sleep(500 + _context.random().nextInt(512)); } catch (InterruptedException ie) {}
                    }
                    
                    // do read
                    try {
                        I2NPMessage msg = _handler.readMessage(_stream);
                        if (msg != null) {
                            long msToRead = _handler.getLastReadTime();
                            int bytesRead = _handler.getLastSize();
                            msToRead += injectLag(bytesRead);
                            _listener.messageReceived(I2NPMessageReader.this, msg, msToRead, bytesRead);
                        }
                    } catch (I2NPMessageException ime) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Error handling message", ime);
                        _listener.readError(I2NPMessageReader.this, ime);
                        _listener.disconnected(I2NPMessageReader.this);
                        cancelRunner();
                    } catch (InterruptedIOException iioe) { 
                        // not all I2NPMessageReaders support this, but some run off sockets which throw
                        // SocketTimeoutExceptions or InterruptedIOExceptions
                        if (_log.shouldLog(Log.INFO))
                            _log.info("Disconnecting due to inactivity", iioe);
                        _listener.disconnected(I2NPMessageReader.this);
                        cancelRunner();
                    } catch (IOException ioe) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("IO Error handling message", ioe);
                        _listener.disconnected(I2NPMessageReader.this);
                        cancelRunner();
                    } catch (Exception e) {
                        _log.log(Log.CRIT, "wtf, error reading", e);
                        _listener.readError(I2NPMessageReader.this, e);
                        _listener.disconnected(I2NPMessageReader.this);
                        cancelRunner();
                    }
                }
                // ??? unused
                if (_stayAlive && !_doRun) {
                    // pause .5 secs when we're paused
                    try { Thread.sleep(500); } catch (InterruptedException ie) {}
                }
            }
            // boom bye bye bad bwoy
        }
        
        private final long injectLag(int size) {
            if (true) { 
                return 0;
            } else {
                boolean shouldLag = _context.random().nextInt(1000) > size;
                if (!shouldLag) return 0;
                
                long readLag = getReadLag();
                if (readLag > 0) {
                    long lag = _context.random().nextLong(readLag);
                    if (lag > 0) {
                        try { Thread.sleep(lag); } catch (InterruptedException ie) {}
                        return lag;
                    } else {
                        return 0;
                    }
                } else {
                    return 0;
                }
            }
        }
        
        private final long getReadLag() {
            try { 
                return Long.parseLong(_context.getProperty("router.injectLagMs", "0")); 
            } catch (NumberFormatException nfe) {
                return 0;
            }
        }
    }
}
