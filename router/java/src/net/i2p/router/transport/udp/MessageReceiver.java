package net.i2p.router.transport.udp;

import java.util.ArrayList;
import java.util.List;

import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataFormatException;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageImpl;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.router.RouterContext;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Pull fully completed fragments off the {@link InboundMessageFragments} queue,
 * parse 'em into I2NPMessages, and stick them on the 
 * {@link net.i2p.router.InNetMessagePool} by way of the {@link UDPTransport}.
 */
public class MessageReceiver implements Runnable {
    private RouterContext _context;
    private Log _log;
    private UDPTransport _transport;
    /** list of messages (InboundMessageState) fully received but not interpreted yet */
    private List _completeMessages;
    private boolean _alive;
    
    public MessageReceiver(RouterContext ctx, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(MessageReceiver.class);
        _transport = transport;
        _completeMessages = new ArrayList(16);
        _alive = true;
    }

    public void startup() {
        _alive = true;
        I2PThread t = new I2PThread(this, "UDP message receiver");
        t.setDaemon(true);
        t.start();
    }
    public void shutdown() {
        _alive = false;
        synchronized (_completeMessages) {
            _completeMessages.clear();
            _completeMessages.notifyAll();
        }
    }
    
    public void receiveMessage(InboundMessageState state) {
        synchronized (_completeMessages) {
            _completeMessages.add(state);
            _completeMessages.notifyAll();
        }
    }
    
    public void run() {
        InboundMessageState message = null;
        while (_alive) {
            try {
                synchronized (_completeMessages) {
                    if (_completeMessages.size() > 0)
                        message = (InboundMessageState)_completeMessages.remove(0);
                    else
                        _completeMessages.wait();
                }
            } catch (InterruptedException ie) {}
            
            if (message != null) {
                int size = message.getCompleteSize();
                if (_log.shouldLog(Log.INFO))
                    _log.info("Full message received (" + message.getMessageId() + ") after " + message.getLifetime() 
                              + "... todo: parse and plop it onto InNetMessagePool");
                I2NPMessage msg = readMessage(message);
                if (msg != null)
                    _transport.messageReceived(msg, null, message.getFrom(), message.getLifetime(), size);
                message = null;
            }
        }
    }
    
    private I2NPMessage readMessage(InboundMessageState state) {
        try {
            byte buf[] = new byte[state.getCompleteSize()];
            ByteArray fragments[] = state.getFragments();
            int numFragments = state.getFragmentCount();
            int off = 0;
            for (int i = 0; i < numFragments; i++) {
                System.arraycopy(fragments[i].getData(), 0, buf, off, fragments[i].getValid());
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Raw fragment[" + i + "] for " + state.getMessageId() + ": " 
                               + Base64.encode(fragments[i].getData(), 0, fragments[i].getValid()));
                off += fragments[i].getValid();
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Raw byte array for " + state.getMessageId() + ": " + Base64.encode(buf));
            I2NPMessage m = I2NPMessageImpl.fromRawByteArray(_context, buf, 0, buf.length);
            m.setUniqueId(state.getMessageId());
            return m;
        } catch (I2NPMessageException ime) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Message invalid: " + state, ime);
            return null;
        } catch (Exception e) {
            _log.log(Log.CRIT, "Error dealing with a message: " + state, e);
            return null;
        } finally {
            state.releaseResources();
        }
    }
}
