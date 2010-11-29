package net.i2p.client.streaming;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.i2p.data.Destination;

/**
 * Bridge between the full streaming lib and the I2PSocket API
 *
 */
class I2PSocketFull implements I2PSocket {
    private Connection _connection;
    private I2PSocket.SocketErrorListener _listener;
    private Destination _remotePeer;
    private Destination _localPeer;
    
    public I2PSocketFull(Connection con) {
        _connection = con;
        if (con != null) {
            _remotePeer = con.getRemotePeer();
            _localPeer = con.getSession().getMyDestination();
        }
    }
    
    public void close() throws IOException {
        Connection c = _connection;
        if (c == null) return;
        if (c.getIsConnected()) {
            OutputStream out = c.getOutputStream();
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ioe) {
                    // ignore any write error, as we want to keep on and kill the
                    // con (thanks Complication!)
                }
            }
	    c.disconnect(true);
        } else {
            //throw new IOException("Not connected");
        }
        destroy();
    }
    
    Connection getConnection() { return _connection; }
    
    public InputStream getInputStream() {
        Connection c = _connection;
        if (c != null)
            return c.getInputStream();
        else
            return null;
    }
    
    public I2PSocketOptions getOptions() {
        Connection c = _connection;
        if (c != null)
            return c.getOptions();
        else
            return null;
    }
    
    public OutputStream getOutputStream() throws IOException {
        Connection c = _connection;
        if (c != null)
            return c.getOutputStream();
        else
            return null;
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
        
        c.getInputStream().setReadTimeout((int)ms);
        c.getOptions().setReadTimeout(ms);
    }
    
    public void setSocketErrorListener(I2PSocket.SocketErrorListener lsnr) {
        _listener = lsnr;
    }
    
    public boolean isClosed() { 
        Connection c = _connection;
        return ((c == null) ||
                (!c.getIsConnected()) || 
                (c.getResetReceived()) ||
                (c.getResetSent()));
    }
    
    void destroy() { 
        Connection c = _connection;
        _connection = null; 
        _listener = null;
        if (c != null)
            c.disconnectComplete();
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
