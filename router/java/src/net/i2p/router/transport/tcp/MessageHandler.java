package net.i2p.router.transport.tcp;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.RouterIdentity;
import net.i2p.data.i2np.I2NPMessageReader;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.DeliveryStatusMessage;
import net.i2p.router.Router;
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
        transport.getContext().statManager().createRateStat("tcp.disconnectAfterSkew", "How skewed a connection became before we killed it?", "TCP", new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l } );
    }
    
    public void disconnected(I2NPMessageReader reader) {
        _con.closeConnection();
    }
    
    public void messageReceived(I2NPMessageReader reader, I2NPMessage message, long msToRead, int size) {
        _con.messageReceived();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Just received message " + message.getUniqueId() + " from " 
                       + _identHash.toBase64().substring(0,6)
                       + " readTime = " + msToRead + "ms type = " + message.getClass().getName());
        if (message instanceof DeliveryStatusMessage) {
            DeliveryStatusMessage msg = (DeliveryStatusMessage)message;
            if ( (msg.getMessageId() == 0) && (msg.getUniqueId() == 0) ) {
                timeMessageReceived(msg.getArrival().getTime());
                // dont propogate the message, its just a fake
                return;
            }
        }
        _transport.messageReceived(message, _ident, _identHash, msToRead, size);
    }
    
    private void timeMessageReceived(long remoteTime) {
        long delta = _con.getRouterContext().clock().now() - remoteTime;
        if ( (delta > Router.CLOCK_FUDGE_FACTOR) || (delta < 0 - Router.CLOCK_FUDGE_FACTOR) ) {
            _con.closeConnection();
            _transport.addConnectionErrorMessage("Peer " + _identHash.toBase64().substring(0,6) 
                                                 + " is too far skewed (" 
                                                 + DataHelper.formatDuration(delta) + ") after uptime of " 
                                                 + DataHelper.formatDuration(_con.getLifetime())); 
            _transport.getContext().statManager().addRateData("tcp.disconnectAfterSkew", delta, _con.getLifetime());
        } else {
            int level = Log.DEBUG;
            if ( (delta > Router.CLOCK_FUDGE_FACTOR/2) || (delta < 0 - Router.CLOCK_FUDGE_FACTOR/2) )
                level = Log.WARN;
            if (_log.shouldLog(level))
                _log.log(level, "Peer " + _identHash.toBase64().substring(0,6) + " is only skewed by (" 
                                + DataHelper.formatDuration(delta) + ") after uptime of " 
                                + DataHelper.formatDuration(_con.getLifetime()) );
        }   
    }
    
    public void readError(I2NPMessageReader reader, Exception error) {
        _con.closeConnection();
    }
    
}
