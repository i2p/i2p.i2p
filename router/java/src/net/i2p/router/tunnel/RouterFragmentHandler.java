package net.i2p.router.tunnel;

import net.i2p.router.RouterContext;

/**
 * Minor extension to allow message history integration
 */
public class RouterFragmentHandler extends FragmentHandler {
    private RouterContext _routerContext;
    
    public RouterFragmentHandler(RouterContext context, DefragmentedReceiver receiver) {
        super(context, receiver);
        _routerContext = context;
    }
    
    protected void noteReception(long messageId, int fragmentId) {
        _routerContext.messageHistory().receiveTunnelFragment(messageId, fragmentId);
    }
    protected void noteCompletion(long messageId) {
        _routerContext.messageHistory().receiveTunnelFragmentComplete(messageId);
    }
    protected void noteFailure(long messageId) {
        _routerContext.messageHistory().droppedFragmentedMessage(messageId);
    }
}
