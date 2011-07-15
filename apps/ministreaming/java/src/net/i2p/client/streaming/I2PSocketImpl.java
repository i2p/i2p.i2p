package net.i2p.client.streaming;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.data.Destination;
import net.i2p.util.Clock;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;


/**
 * Initial stub implementation for the socket
 *
 * @deprecated use I2PSocketFull
 */
class I2PSocketImpl implements I2PSocket {
    private final static Log _log = new Log(I2PSocketImpl.class);

    public static final int MAX_PACKET_SIZE = 1024 * 32;
    public static final int PACKET_DELAY = 100;

    private I2PSocketManagerImpl manager;
    private Destination local;
    private Destination remote;
    private String localID;
    private String remoteID;
    private final Object remoteIDWaiter = new Object();
    private I2PInputStream in;
    private I2POutputStream out;
    private I2PSocket.SocketErrorListener _socketErrorListener;
    private boolean outgoing;
    private long _socketId;
    private static long __socketId = 0;
    private long _bytesRead = 0;
    private long _bytesWritten = 0;
    private long _createdOn;
    private long _closedOn;
    private long _remoteIdSetTime;
    private I2PSocketOptions _options;
    private final Object flagLock = new Object();

    /**
     * Whether the I2P socket has already been closed.
     */
    private boolean closed = false;

    /**
     * Whether to send out a close packet when the socket is
     * closed. (If the socket is closed because of an incoming close
     * packet, we need not send one.)
     */
    private boolean sendClose = true;

    /**
     * Whether the I2P socket has already been closed and all data
     * (from I2P to the app, dunno whether to call this incoming or
     * outgoing) has been processed.
     */
    private boolean closed2 = false;

    /**
     * @param peer who this socket is (or should be) connected to 
     * @param mgr how we talk to the network
     * @param outgoing did we initiate the connection (true) or did we receive it (false)?
     * @param localID what is our half of the socket ID?
     */
    public I2PSocketImpl(Destination peer, I2PSocketManagerImpl mgr, boolean outgoing, String localID) {
        this.outgoing = outgoing;
        manager = mgr;
        remote = peer;
        _socketId = ++__socketId;
        local = mgr.getSession().getMyDestination();
        String us = mgr.getSession().getMyDestination().calculateHash().toBase64().substring(0,4);
        String name = us + (outgoing ? "->" : "<-") + peer.calculateHash().toBase64().substring(0,4);
        in = new I2PInputStream(name + " in");
        I2PInputStream pin = new I2PInputStream(name + " out");
        out = new I2POutputStream(pin);
        new I2PSocketRunner(pin);
        this.localID = localID;
        _createdOn = I2PAppContext.getGlobalContext().clock().now();
        _remoteIdSetTime = -1;
        _closedOn = -1;
        _options = mgr.getDefaultOptions();
    }

    /**
     * Our half of the socket's unique ID
     *
     */
    public String getLocalID() {
        return localID;
    }

    /**
     * We've received the other side's half of the socket's unique ID 
     */
    public void setRemoteID(String id) {
        synchronized (remoteIDWaiter) {
            remoteID = id;
            _remoteIdSetTime = System.currentTimeMillis();
            remoteIDWaiter.notifyAll();
        }
    }

    /**
     * Retrieve the other side's half of the socket's unique ID, or null if it
     * isn't known yet
     *
     * @param wait if true, we should wait until we receive it from the peer, otherwise
     *             return what we know immediately (which may be null)
     */
    public String getRemoteID(boolean wait) {
        try {
            return getRemoteID(wait, -1);
        } catch (InterruptedIOException iie) {
            _log.error("wtf, we said we didn't want it to time out!  you smell", iie);
            return null;
        }
    }

    /**
     * Retrieve the other side's half of the socket's unique ID, or null if it isn't
     * known yet and we were instructed not to wait
     *
     * @param wait should we wait for the peer to send us their half of the ID, or 
     *             just return immediately?
     * @param maxWait if we're going to wait, after how long should we timeout and fail?
     *                (if this value is < 0, we wait indefinitely)
     * @throws InterruptedIOException when the max waiting period has been exceeded
     */
    public String getRemoteID(boolean wait, long maxWait) throws InterruptedIOException {
        long dieAfter = System.currentTimeMillis() + maxWait;
        synchronized (remoteIDWaiter) {
            if (wait) {
                if (remoteID == null) {
                    try {
                        if (maxWait >= 0)
                            remoteIDWaiter.wait(maxWait);
                        else
                            remoteIDWaiter.wait();
                    } catch (InterruptedException ex) {
                    }
                }

                long now = System.currentTimeMillis();
                if ((maxWait >= 0) && (now >= dieAfter)) {
                    long waitedExcess = now - dieAfter;
                    throw new InterruptedIOException("Timed out waiting for remote ID (waited " + waitedExcess 
                                                     + "ms too long [" + maxWait + "ms, remId " + remoteID
                                                     + ", remId set " + (now-_remoteIdSetTime) + "ms ago])");
                }
             
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("TIMING: RemoteID set to " 
                               + I2PSocketManagerImpl.getReadableForm(remoteID) + " for "
                               + this.hashCode());
            }
            return remoteID;
        }
    }

    /**
     * Retrieve the other side's half of the socket's unique ID, or null if it
     * isn't known yet.  This does not wait
     *
     */
    public String getRemoteID() {
        return getRemoteID(false);
    }

    /**
     * The other side has given us some data, so inject it into our socket's 
     * inputStream
     *
     * @param data the data to inject into our local inputStream
     */
    public void queueData(byte[] data) {
        _bytesRead += data.length;
        try {
            in.queueData(data, false);
        } catch (IOException ioe) {
            _log.log(Log.CRIT, "wtf, we said DONT block, how can we timeout?", ioe);
        }
    }

    /**
     * Return the Destination of this side of the socket.
     */
    public Destination getThisDestination() {
        return local;
    }

    /**
     * Return the destination of the peer.
     */
    public Destination getPeerDestination() {
        return remote;
    }

    /**
     * Return an InputStream to read from the socket.
     */
    public InputStream getInputStream() throws IOException {
        if ((in == null)) throw new IOException("Not connected");
        return in;
    }

    /**
     * Return an OutputStream to write into the socket.
     */
    public OutputStream getOutputStream() throws IOException {
        if ((out == null)) throw new IOException("Not connected");
        return out;
    }

    /**
     * Closes the socket if not closed yet (from the Application
     * side).
     */
    public void close() throws IOException {
        synchronized (flagLock) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Closing connection");
            closed = true;
            _closedOn = I2PAppContext.getGlobalContext().clock().now();
        }
        out.close();
        in.notifyClosed();
    }

    public boolean isClosed() { return _closedOn > 0; }
    
    /**
     * Close the socket from the I2P side (by a close packet)
     */
    protected void internalClose() {
        synchronized (flagLock) {
            closed = true;
            closed2 = true;
            sendClose = false;
            _closedOn = I2PAppContext.getGlobalContext().clock().now();
        }
        out.close();
        in.notifyClosed();
    }

    private byte getMask(int add) {
        if (outgoing)
            return (byte)(I2PSocketManagerImpl.DATA_IN + (byte)add);
        else
            return (byte)(I2PSocketManagerImpl.DATA_OUT + (byte)add);
    }

    public void setOptions(I2PSocketOptions options) {
        _options = options;
        in.setReadTimeout(options.getReadTimeout());
    }
    
    public I2PSocketOptions getOptions() { 
        return _options;
    }
    
    /**
     * How long we will wait blocked on a read() operation.  This is simply a
     * helper to query the I2PSocketOptions
     *
     * @return milliseconds to wait, or -1 if we will wait indefinitely
     */
    public long getReadTimeout() {
        return _options.getReadTimeout();
    }

    /**
     * Define how long we will wait blocked on a read() operation (-1 will make
     * the socket wait forever).  This is simply a helper to adjust the 
     * I2PSocketOptions
     *
     */
    public void setReadTimeout(long ms) {
        _options.setReadTimeout(ms);
        in.setReadTimeout(ms);
    }
    
    public void setSocketErrorListener(I2PSocket.SocketErrorListener lsnr) {
        _socketErrorListener = lsnr;
    }
    
    void errorOccurred() {
        if (_socketErrorListener != null)
            _socketErrorListener.errorOccurred();
    }
    
    public long getBytesSent() { return _bytesWritten; }
    public long getBytesReceived() { return _bytesRead; }
    public long getCreatedOn() { return _createdOn; }
    public long getClosedOn() { return _closedOn; }
    
    /**
     * The remote port.
     * @return 0 always
     * @since 0.8.9
     */
    public int getPort() {
        return I2PSession.PORT_UNSPECIFIED;
    }

    /**
     * The local port.
     * @return 0 always
     * @since 0.8.9
     */
    public int getLocalPort() {
        return I2PSession.PORT_UNSPECIFIED;
    }
    
    
    private String getPrefix() { return "[" + _socketId + "]: "; }
    
    //--------------------------------------------------
    private class I2PInputStream extends InputStream {
        private String streamName;
        private final ByteCollector bc = new ByteCollector();
        private boolean inStreamClosed = false;

        private long readTimeout = -1;

        public I2PInputStream(String name) {
            streamName = name;
        }
        
        public long getReadTimeout() {
            return readTimeout;
        }
        
        private String getStreamPrefix() { 
            return getPrefix() + streamName + ": ";
        }
        
        public void setReadTimeout(long ms) {
            readTimeout = ms;
        }
        
        public int read() throws IOException {
            byte[] b = new byte[1];
            int res = read(b);
            if (res == 1) return b[0] & 0xff;
            if (res == -1) return -1;
            throw new RuntimeException("Incorrect read() result");
        }

        // I have to ask if this method is really needed, since the JDK has this already,
	// including the timeouts. Perhaps the need is for debugging more than anything
	// else?
        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getStreamPrefix() + "Read called for " + len + " bytes (avail=" 
                           + bc.getCurrentSize() + "): " + this.hashCode());
            if (len == 0) return 0;
            long dieAfter = System.currentTimeMillis() + readTimeout;
            byte[] read = null;
            synchronized (bc) {
                read = bc.startToByteArray(len);
                bc.notifyAll();
            }
            boolean timedOut = false;

            while ( (read.length == 0) && (!inStreamClosed) ) {
                synchronized (flagLock) {
                    if (closed) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug(getStreamPrefix() + "Closed is set after reading " 
                                       + _bytesRead + " and writing " + _bytesWritten 
                                       + ", so closing stream: " + hashCode());
                        return -1;
                    }
                }
                try {
                    synchronized (I2PSocketImpl.I2PInputStream.this) {
                        if (readTimeout >= 0) {
                            wait(readTimeout);
                        } else {
                            wait();
                        }
                    }
                } catch (InterruptedException ex) {}

                if ((readTimeout >= 0)
                    && (System.currentTimeMillis() >= dieAfter)) {
                    throw new InterruptedIOException(getStreamPrefix() + "Timeout reading from I2PSocket (" 
                                                     + readTimeout + " msecs)");
                }

                synchronized (bc) {
                    read = bc.startToByteArray(len);
                    bc.notifyAll();
                }
            }
            if (read.length > len) throw new RuntimeException("BUG");
            if ( (inStreamClosed) && (read.length <= 0) )
                return -1;
            
            System.arraycopy(read, 0, b, off, read.length);

            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug(getStreamPrefix() + "Read from I2PInputStream " + hashCode() + " returned " 
                           + read.length + " bytes");
            }
            //if (_log.shouldLog(Log.DEBUG)) {
            //  _log.debug("Read from I2PInputStream " + this.hashCode()
            //             + " returned "+read.length+" bytes:\n"
            //             + HexDump.dump(read));
            //}
            return read.length;
        }

	/**
	 * @return 0 if empty, > 0 if there is data.
	 */
        @Override
        public int available() {
            synchronized (bc) {
                return bc.getCurrentSize();
            }
        }

        /**
         * Add the data to the queue
         *
         * @param allowBlock if true, we will block if the buffer and the socket options
         *                   say so, otherwise we simply take the data regardless.
         * @throws InterruptedIOException if the queue's buffer is full, the socket has
         *                                a write timeout, and that timeout is exceeded
         * @throws IOException if the connection was closed while queueing up the data
         */
        void queueData(byte[] data, boolean allowBlock) throws InterruptedIOException, IOException {
            queueData(data, 0, data.length, allowBlock);
        }

        /**
         * Add the data to the queue
         *
         * @param allowBlock if true, we will block if the buffer and the socket options
         *                   say so, otherwise we simply take the data regardless.
         * @throws InterruptedIOException if the queue's buffer is full, the socket has
         *                                a write timeout, and that timeout is exceeded
         * @throws IOException if the connection was closed while queueing up the data
         */
        public void queueData(byte[] data, int off, int len, boolean allowBlock) throws InterruptedIOException, IOException {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getStreamPrefix() + "Insert " + len + " bytes into queue: " + hashCode());
            Clock clock = I2PAppContext.getGlobalContext().clock();
            long endAfter = clock.now() + _options.getWriteTimeout();
            synchronized (bc) {
                if (allowBlock) {
                    if (_options.getMaxBufferSize() > 0) {
                        while (bc.getCurrentSize() > _options.getMaxBufferSize()) {
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug(getStreamPrefix() + "Buffer size exceeded: pending " 
                                           + bc.getCurrentSize() + " limit " + _options.getMaxBufferSize());
                            if (_options.getWriteTimeout() > 0) {
                                long timeLeft = endAfter - clock.now();
                                if (timeLeft <= 0) {
                                    long waited = _options.getWriteTimeout() - timeLeft;
                                    throw new InterruptedIOException(getStreamPrefix() + "Waited too long (" 
                                                                     + waited + "ms) to write " 
                                                                     + len + " with a buffer at " + bc.getCurrentSize());
                                }
                            }
                            if (inStreamClosed)
                                throw new IOException(getStreamPrefix() + "Stream closed while writing");
                            if (_closedOn > 0)
                                throw new IOException(getStreamPrefix() + "I2PSocket closed while writing");
                            try {
                                bc.wait(1000);
                            } catch (InterruptedException ie) {}
                        }
                    }
                }
                bc.append(data, off, len);
            }
            synchronized (I2PInputStream.this) {
                I2PInputStream.this.notifyAll();
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getStreamPrefix() + "After insert " + len + " bytes into queue: " + hashCode());
        }

        public void notifyClosed() {
            synchronized (I2PInputStream.this) {
                I2PInputStream.this.notifyAll();
            }
        }
        
        @Override
        public void close() throws IOException {
            super.close();
            notifyClosed();
            synchronized (bc) {
                inStreamClosed = true;
                bc.notifyAll();
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getStreamPrefix() + "After close");
        }

    }

    private class I2POutputStream extends OutputStream {

        public I2PInputStream sendTo;

        public I2POutputStream(I2PInputStream sendTo) {
            this.sendTo = sendTo;
        }

        public void write(int b) throws IOException {
            write(new byte[] { (byte) b});
        }

	// This override is faster than the built in JDK, 
	// but there are other variations not handled
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            _bytesWritten += len;
            sendTo.queueData(b, off, len, true);
        }

        @Override
        public void close() {
            sendTo.notifyClosed();
        }
    }

    private static volatile long __runnerId = 0;
    private class I2PSocketRunner extends I2PThread {

        public InputStream in;

        public I2PSocketRunner(InputStream in) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getPrefix() + "Runner's input stream is: " + in.hashCode());
            this.in = in;
            String peer = I2PSocketImpl.this.remote.calculateHash().toBase64();
            setName("SocketRunner " + (++__runnerId) + "/" + _socketId + " " + peer.substring(0, 4));
            start();
        }
        
        /**
         * Pump some more data
         *
         * @return true if we should keep on handling, false otherwise
         */
        private boolean handleNextPacket(ByteCollector bc, byte buffer[]) 
                                         throws IOException, I2PSessionException {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getPrefix() + ":" + Thread.currentThread().getName() + "handleNextPacket");
            int len = in.read(buffer);
            int bcsize = 0;
            synchronized (bc) {
                bcsize = bc.getCurrentSize();
            }

            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getPrefix() + ":" + Thread.currentThread().getName() + "handleNextPacket len=" + len + " bcsize=" + bcsize);

            if (len != -1) {
                synchronized (bc) {
                    bc.append(buffer, len);
                }
            } else if (bcsize == 0) {
                // nothing left in the buffer, and read(..) got EOF (-1).
                // the bart the
                return false;
            }
            if ((bcsize < MAX_PACKET_SIZE) && (in.available() == 0)) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(getPrefix() + ":" + Thread.currentThread().getName() + "Runner Point d: " + hashCode());

                try {
                    Thread.sleep(PACKET_DELAY);
                } catch (InterruptedException e) {
                    _log.warn("wtf", e);
                }
            }
            if ((bcsize >= MAX_PACKET_SIZE) || (in.available() == 0)) {
                byte data[] = null;
                synchronized (bc) {
                    data = bc.startToByteArray(MAX_PACKET_SIZE);
                }
                if (data.length > 0) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug(getPrefix() + ":" + Thread.currentThread().getName() + "Message size is: " + data.length);
                    boolean sent = sendBlock(data);
                    if (!sent) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn(getPrefix() + ":" + Thread.currentThread().getName() + "Error sending message to peer.  Killing socket runner");
                        errorOccurred();
                        return false;
                    } else {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug(getPrefix() + ":" + Thread.currentThread().getName() + "Message sent to peer");
                    }
                }
            }
            return true;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[MAX_PACKET_SIZE];
            ByteCollector bc = new ByteCollector();
            boolean keepHandling = true;
            int packetsHandled = 0;
            try {
                //              try {
                while (keepHandling) {
                    keepHandling = handleNextPacket(bc, buffer);
                    packetsHandled++;
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug(getPrefix() + ":" + Thread.currentThread().getName() 
                                   + "Packets handled: " + packetsHandled);
                }
                if (_log.shouldLog(Log.INFO))
                    _log.info(getPrefix() + ":" + Thread.currentThread().getName() 
                               + "After handling packets, we're done.  Packets handled: " + packetsHandled);
                
                if ((bc.getCurrentSize() > 0) && (packetsHandled > 1)) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn(getPrefix() + "We lost some data queued up due to a network send error (input stream: " 
                                  + in.hashCode() + "; "
                                  + "queue size: " + bc.getCurrentSize() + ")");
                }
                synchronized (flagLock) {
                    closed2 = true;
                }
                boolean sc;
                synchronized (flagLock) {
                    sc = sendClose;
                } // FIXME: Race here?
                if (sc) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info(getPrefix() + ":" + Thread.currentThread().getName() 
                                  + "Sending close packet: (we started? " + outgoing 
                                  + ") after reading " + _bytesRead + " and writing " + _bytesWritten);
                    byte[] packet = I2PSocketManagerImpl.makePacket(getMask(0x02), remoteID, new byte[0]);
                    boolean sent = manager.getSession().sendMessage(remote, packet);
                    if (!sent) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn(getPrefix() + ":" + Thread.currentThread().getName() 
                                      + "Error sending close packet to peer");
                        errorOccurred();
                    }
                }
                manager.removeSocket(I2PSocketImpl.this);
                internalClose();
            } catch (InterruptedIOException ex) {
                _log.error(getPrefix() + "BUG! read() operations should not timeout!", ex);
            } catch (IOException ex) {
                // WHOEVER removes this event on inconsistent
                // state before fixing the inconsistent state (a
                // reference on the socket in the socket manager
                // etc.) will get hanged by me personally -- mihi
                _log.error(getPrefix() + "Error running - **INCONSISTENT STATE!!!**", ex);
            } catch (I2PException ex) {
                _log.error(getPrefix() + "Error running - **INCONSISTENT STATE!!!**", ex);
            }
        }

        private boolean sendBlock(byte data[]) throws I2PSessionException {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getPrefix() + "TIMING: Block to send for " + I2PSocketImpl.this.hashCode());
            if (remoteID == null) {
                _log.error(getPrefix() + "NULL REMOTEID");
                return false;
            }
            byte[] packet = I2PSocketManagerImpl.makePacket(getMask(0x00), remoteID, data);
            boolean sent;
            synchronized (flagLock) {
                if (closed2) return false;
            }
            sent = manager.getSession().sendMessage(remote, packet);
            return sent;
        }
    }

    @Override
    public String toString() { return "" + hashCode(); }
}
