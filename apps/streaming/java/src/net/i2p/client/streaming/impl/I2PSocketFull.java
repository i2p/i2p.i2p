package net.i2p.client.streaming.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SelectableChannel;
import java.util.concurrent.atomic.AtomicBoolean;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 * Bridge between the full streaming lib and the I2PSocket API
 *
 */
class I2PSocketFull implements I2PSocket {
    private final Log log;
    private volatile Connection _connection;
    private final Destination _remotePeer;
    private final Destination _localPeer;
    private final AtomicBoolean _closed = new AtomicBoolean();
    
    public I2PSocketFull(Connection con, I2PAppContext context) {
        log = context.logManager().getLog(I2PSocketFull.class);
        _connection = con;
        if (con != null) {
            _remotePeer = con.getRemotePeer();
            _localPeer = con.getSession().getMyDestination();
        } else
            _remotePeer = _localPeer = null;
    }
    
    /**
     *  Closes this socket.
     *
     *  Nonblocking as of 0.9.9:
     *  Any thread currently blocked in an I/O operation upon this socket will throw an IOException.
     *  Once a socket has been closed, it is not available for further networking use
     *  (i.e. can't be reconnected or rebound). A new socket needs to be created.
     *  Closing this socket will also close the socket's InputStream and OutputStream.
     */
    public void close() throws IOException {
        if (!_closed.compareAndSet(false,true)) {
            // log a trace to find out why
            log.logCloseLoop("I2PSocket",_localPeer,"-->",_remotePeer,_connection);
            return;
        }
        Connection c = _connection;
        if (c == null) return;
        if (log.shouldLog(Log.INFO))
            log.info("close() called, connected? " + c.getIsConnected() + " : " + c, new Exception());
        if (c.getIsConnected()) {
            MessageInputStream in = c.getInputStream();
            in.close();
            MessageOutputStream out = c.getOutputStream();
            out.closeInternal();
            // this will cause any thread waiting in Connection.packetSendChoke()
            // to throw an IOE
            c.windowAdjusted();
        } else {
            //throw new IOException("Not connected");
        }
        destroy();
    }
    
    /**
     *  Resets and closes this socket. Sends a RESET indication to the far-end.
     *  This is the equivalent of setSoLinger(true, 0) followed by close() on a Java Socket.
     *
     *  Nonblocking.
     *  Any thread currently blocked in an I/O operation upon this socket will throw an IOException.
     *  Once a socket has been reset, it is not available for further networking use
     *  (i.e. can't be reconnected or rebound). A new socket needs to be created.
     *  Resetting this socket will also close the socket's InputStream and OutputStream.
     *
     *  @since 0.9.30
     */
    public void reset() throws IOException {
        Connection c = _connection;
        if (c == null) return;
        if (log.shouldLog(Log.INFO))
            log.info("reset() called, connected? " + c.getIsConnected() + " : " + c, new Exception());
        if (c.getIsConnected()) {
            c.disconnect(false);
            // this will cause any thread waiting in Connection.packetSendChoke()
            // to throw an IOE
            c.windowAdjusted();
        }
        destroy();
    }
    
    Connection getConnection() { return _connection; }
    
    /**
     *  As of 0.9.9 will throw an IOE if socket is closed.
     *  Prior to that would return null instead of throwing IOE.
     *  @return non-null
     */
    public InputStream getInputStream() throws IOException {
        Connection c = _connection;
        if (c != null)
            return c.getInputStream();
        throw new IOException("Socket closed");
    }
    
    public I2PSocketOptions getOptions() {
        Connection c = _connection;
        if (c != null)
            return c.getOptions();
        else
            return null;
    }

    /**
     *  Unimplemented, unlikely to ever be implemented.
     *
     *  @deprecated
     *  @return null always
     *  @since 0.8.9
     */
    @Deprecated
    public synchronized SelectableChannel getChannel() {
        return null;
    }
    
    /**
     *  As of 0.9.9 will throw an IOE if socket is closed.
     *  Prior to that would return null instead of throwing IOE.
     *  @return non-null
     */
    public OutputStream getOutputStream() throws IOException {
        Connection c = _connection;
        if (c != null)
            return c.getOutputStream();
        throw new IOException("Socket closed");
    }
    
    public Destination getPeerDestination() { return _remotePeer; }
    
    public long getReadTimeout() {
        I2PSocketOptions opts = getOptions();
        if (opts != null) 
            return opts.getReadTimeout();
        else 
            return -1;
    }
    
    public Destination getThisDestination() { return _localPeer; }
    
    public void setOptions(I2PSocketOptions options) {
        Connection c = _connection;
        if (c == null) return;
        
        if (options instanceof ConnectionOptions)
            c.setOptions((ConnectionOptions)options);
        else
            c.setOptions(new ConnectionOptions(options));
    }
    
    public void setReadTimeout(long ms) {
        Connection c = _connection;
        if (c == null) return;
        
        if (ms > Integer.MAX_VALUE)
            ms = Integer.MAX_VALUE;
        c.getInputStream().setReadTimeout((int)ms);
        c.getOptions().setReadTimeout(ms);
    }
    
    /**
     *  Deprecated, unimplemented, does nothing
     */
    public void setSocketErrorListener(I2PSocket.SocketErrorListener lsnr) {
    }
    
    public boolean isClosed() { 
        Connection c = _connection;
        return ((c == null) ||
                (!c.getIsConnected()) || 
                (c.getResetReceived()) ||
                (c.getResetSent()));
    }
    
    void destroy() { 
        destroy2();
    }
    
    /**
     *  Call from Connection.disconnectComplete()
     *  instead of destroy() so we don't loop
     *  @since 0.8.13
     */
    void destroy2() { 
        _connection = null;
    }
    
    /**
     * The remote port.
     * @return the port or 0 if unknown
     * @since 0.8.9
     */
    public int getPort() {
        Connection c = _connection;
        return c == null ? I2PSession.PORT_UNSPECIFIED : c.getPort();
    }

    /**
     * The local port.
     * @return the port or 0 if unknown
     * @since 0.8.9
     */
    public int getLocalPort() {
        Connection c = _connection;
        return c == null ? I2PSession.PORT_UNSPECIFIED : c.getLocalPort();
    }

    @Override
    public String toString() {
        Connection c = _connection;
        if (c == null)
            return super.toString();
        else
            return c.toString();
    }
}
