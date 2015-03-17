package net.i2p.router.tunnel;

import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Minor extension to allow message history integration
 */
class RouterFragmentHandler extends FragmentHandler {
    
    public RouterFragmentHandler(RouterContext context, DefragmentedReceiver receiver) {
        super(context, receiver);
    }
    
    @Override
    protected void noteReception(long messageId, int fragmentId, Object status) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Received fragment " + fragmentId + " for message " + messageId + ": " + status);
        _context.messageHistory().receiveTunnelFragment(messageId, fragmentId, status);
    }
    @Override
    protected void noteCompletion(long messageId) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Received complete message " + messageId);
        _context.messageHistory().receiveTunnelFragmentComplete(messageId);
    }
    @Override
    protected void noteFailure(long messageId, String status) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Dropped message " + messageId + ": " + status);
        _context.messageHistory().droppedFragmentedMessage(messageId, status);
    }
}
