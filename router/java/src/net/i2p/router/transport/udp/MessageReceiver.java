package net.i2p.router.transport.udp;

import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataFormatException;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageImpl;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Pull fully completed fragments off the {@link InboundMessageFragments} queue,
 * parse 'em into I2NPMessages, and stick them on the 
 * {@link net.i2p.router.InNetMessagePool} by way of the {@link UDPTransport}.
 */
public class MessageReceiver implements Runnable {
    private RouterContext _context;
    private Log _log;
    private InboundMessageFragments _fragments;
    private UDPTransport _transport;
    
    public MessageReceiver(RouterContext ctx, InboundMessageFragments frag, UDPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(MessageReceiver.class);
        _fragments = frag;
        _transport = transport;
    }

    public void run() {
        while (_fragments.isAlive()) {
            InboundMessageState message = _fragments.receiveNextMessage();
            if (message == null) continue;
            
            int size = message.getCompleteSize();
            if (_log.shouldLog(Log.INFO))
                _log.info("Full message received (" + message.getMessageId() + ") after " + message.getLifetime() 
                          + "... todo: parse and plop it onto InNetMessagePool");
            I2NPMessage msg = readMessage(message);
            if (msg != null)
                _transport.messageReceived(msg, null, message.getFrom(), message.getLifetime(), size);
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
            if (_log.shouldLog(Log.WARN))
                _log.warn("Message invalid: " + state, ime);
            return null;
        } catch (Exception e) {
            _log.log(Log.CRIT, "Error dealing with a message: " + state, e);
            return null;
        } finally {
            state.releaseResources();
        }
    }
}
