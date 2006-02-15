package net.i2p.router.transport.udp;

import java.util.ArrayList;
import java.util.List;

import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataFormatException;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageImpl;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.data.i2np.I2NPMessageHandler;
import net.i2p.router.RouterContext;
import net.i2p.util.ByteCache;
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
    private ByteCache _cache;
    private I2NPMessageHandler _handler;
    
    public MessageReceiver(RouterContext ctx, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(MessageReceiver.class);
        _transport = transport;
        _completeMessages = new ArrayList(16);
        _cache = ByteCache.getInstance(64, I2NPMessage.MAX_SIZE);
        _handler = new I2NPMessageHandler(ctx);
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
        ByteArray buf = _cache.acquire();
        
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
                    _log.info("Full message received (" + message.getMessageId() + ") after " + message.getLifetime());
                I2NPMessage msg = readMessage(buf, message);
                if (msg != null)
                    _transport.messageReceived(msg, null, message.getFrom(), message.getLifetime(), size);
                message = null;
            }
        }
        
        // no need to zero it out, as these buffers are only used with an explicit getCompleteSize
        _cache.release(buf, false); 
    }
    
    private I2NPMessage readMessage(ByteArray buf, InboundMessageState state) {
        try {
            //byte buf[] = new byte[state.getCompleteSize()];
            ByteArray fragments[] = state.getFragments();
            int numFragments = state.getFragmentCount();
            int off = 0;
            for (int i = 0; i < numFragments; i++) {
                System.arraycopy(fragments[i].getData(), 0, buf.getData(), off, fragments[i].getValid());
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Raw fragment[" + i + "] for " + state.getMessageId() + ": " 
                               + Base64.encode(fragments[i].getData(), 0, fragments[i].getValid())
                               + " (valid: " + fragments[i].getValid() 
                               + " raw: " + Base64.encode(fragments[i].getData()) + ")");
                off += fragments[i].getValid();
            }
            if (off != state.getCompleteSize())
                _log.error("Hmm, offset of the fragments = " + off + " while the state says " + state.getCompleteSize());
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Raw byte array for " + state.getMessageId() + ": " + Base64.encode(buf.getData(), 0, state.getCompleteSize()));
            I2NPMessage m = I2NPMessageImpl.fromRawByteArray(_context, buf.getData(), 0, state.getCompleteSize(), _handler);
            m.setUniqueId(state.getMessageId());
            return m;
        } catch (I2NPMessageException ime) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Message invalid: " + state, ime);
            _context.messageHistory().droppedInboundMessage(state.getMessageId(), state.getFrom(), "error: " + ime.toString() + ": " + state.toString());
            return null;
        } catch (Exception e) {
            _log.log(Log.CRIT, "Error dealing with a message: " + state, e);
            _context.messageHistory().droppedInboundMessage(state.getMessageId(), state.getFrom(), "error: " + e.toString() + ": " + state.toString());
            return null;
        } finally {
            state.releaseResources();
        }
    }
}
