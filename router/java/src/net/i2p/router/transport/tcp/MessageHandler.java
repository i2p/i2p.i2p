package net.i2p.router.transport.tcp;

import net.i2p.data.Hash;
import net.i2p.data.RouterIdentity;
import net.i2p.data.i2np.I2NPMessageReader;
import net.i2p.data.i2np.I2NPMessage;

/**
 * Receive messages from a message reader and bounce them off to the transport
 * for further enqueueing.
 */
public class MessageHandler implements I2NPMessageReader.I2NPMessageEventListener {
    private TCPTransport _transport;
    private TCPConnection _con;
    private RouterIdentity _ident;
    private Hash _identHash;
    
    public MessageHandler(TCPTransport transport, TCPConnection con) {
        _transport = transport;
        _con = con;
        _ident = con.getRemoteRouterIdentity();
        _identHash = _ident.calculateHash();
    }
    
    public void disconnected(I2NPMessageReader reader) {
        _con.closeConnection();
    }
    
    public void messageReceived(I2NPMessageReader reader, I2NPMessage message, long msToRead) {
        _transport.messageReceived(message, _ident, _identHash, msToRead, message.getSize());
    }
    
    public void readError(I2NPMessageReader reader, Exception error) {
        _con.closeConnection();
    }
    
}
