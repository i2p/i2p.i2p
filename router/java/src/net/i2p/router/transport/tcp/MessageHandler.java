package net.i2p.router.transport.tcp;

import net.i2p.data.Hash;
import net.i2p.data.RouterIdentity;
import net.i2p.data.i2np.I2NPMessageReader;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.util.Log;

/**
 * Receive messages from a message reader and bounce them off to the transport
 * for further enqueueing.
 */
public class MessageHandler implements I2NPMessageReader.I2NPMessageEventListener {
    private Log _log;
    private TCPTransport _transport;
    private TCPConnection _con;
    private RouterIdentity _ident;
    private Hash _identHash;
    
    public MessageHandler(TCPTransport transport, TCPConnection con) {
        _transport = transport;
        _con = con;
        _ident = con.getRemoteRouterIdentity();
        _identHash = _ident.calculateHash();
        _log = con.getRouterContext().logManager().getLog(MessageHandler.class);
    }
    
    public void disconnected(I2NPMessageReader reader) {
        _con.closeConnection();
    }
    
    public void messageReceived(I2NPMessageReader reader, I2NPMessage message, long msToRead) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Just received message " + message.getUniqueId() + " from " 
                       + _identHash.toBase64().substring(0,6)
                       + " readTime = " + msToRead + "ms type = " + message.getClass().getName());
        _transport.messageReceived(message, _ident, _identHash, msToRead, message.getMessageSize());
    }
    
    public void readError(I2NPMessageReader reader, Exception error) {
        _con.closeConnection();
    }
    
}
