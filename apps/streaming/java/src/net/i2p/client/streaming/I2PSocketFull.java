package net.i2p.client.streaming;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.i2p.data.Destination;

/**
 * Bridge between the full streaming lib and the I2PSocket API
 *
 */
public class I2PSocketFull implements I2PSocket {
    private Connection _connection;
    private I2PSocket.SocketErrorListener _listener;
    
    public I2PSocketFull(Connection con) {
        _connection = con;
    }
    
    public void close() throws IOException {
        Connection c = _connection;
        if (c == null) return;
        if (c.getIsConnected()) {
            OutputStream out = c.getOutputStream();
            if (out != null)
                out.close();
            c.disconnect(true);
        } else {
            //throw new IOException("Not connected");
        }
        destroy();
    }
    
    Connection getConnection() { return _connection; }
    
    public InputStream getInputStream() {
        return _connection.getInputStream();
    }
    
    public I2PSocketOptions getOptions() {
        return _connection.getOptions();
    }
    
    public OutputStream getOutputStream() throws IOException {
        return _connection.getOutputStream();
    }
    
    public Destination getPeerDestination() {
        return _connection.getRemotePeer();
    }
    
    public long getReadTimeout() {
        return _connection.getOptions().getReadTimeout();
    }
    
    public Destination getThisDestination() {
        return _connection.getSession().getMyDestination();
    }
    
    public void setOptions(I2PSocketOptions options) {
        if (options instanceof ConnectionOptions)
            _connection.setOptions((ConnectionOptions)options);
        else
            _connection.setOptions(new ConnectionOptions(options));
    }
    
    public void setReadTimeout(long ms) {
        _connection.getOptions().setReadTimeout(ms);
    }
    
    public void setSocketErrorListener(I2PSocket.SocketErrorListener lsnr) {
        _listener = lsnr;
    }
    
    void destroy() { 
        _connection = null; 
        _listener = null;
    }
}
