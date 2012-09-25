package net.i2p.router.web;

import net.i2p.router.RouterContext;
import net.i2p.router.update.ConsoleUpdateManager;
import net.i2p.update.UpdateType;
import static net.i2p.update.UpdateType.*;
import net.i2p.util.Log;

/**
 * <p>Handles the request to update the router by firing one or more
 * {@link net.i2p.util.EepGet} calls to download the latest signed update file
 * and displaying the status to anyone who asks.
 * </p>
 * <p>After the download completes the signed update file is verified with
 * {@link net.i2p.crypto.TrustedUpdate}, and if it's authentic the payload
 * of the signed update file is unpacked and the router is restarted to complete
 * the update process.
 * </p>
 *
 * This is like a FormHandler but we don't extend it, as we don't have the message area, etc.
 */
public class UpdateHandler {
    protected RouterContext _context;
    protected Log _log;
    private String _action;
    private String _nonce;
    
    public UpdateHandler() {
        this(ContextHelper.getContext(null));
    }

    public UpdateHandler(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(UpdateHandler.class);
    }
    
    /**
     * Configure this bean to query a particular router context
     *
     * @param contextId beginning few characters of the routerHash, or null to pick
     *                  the first one we come across.
     */
    public void setContextId(String contextId) {
        try {
            _context = ContextHelper.getContext(contextId);
            _log = _context.logManager().getLog(UpdateHandler.class);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
    
    /** these two can be set in either order, so call checkUpdateAction() twice */
    public void setUpdateAction(String val) {
        _action = val;
        checkUpdateAction();
    }
    
    public void setUpdateNonce(String nonce) { 
        _nonce = nonce;
        checkUpdateAction();
    }

    private void checkUpdateAction() { 
        if (_nonce == null || _action == null) return;
        if (_nonce.equals(System.getProperty("net.i2p.router.web.UpdateHandler.nonce")) ||
            _nonce.equals(System.getProperty("net.i2p.router.web.UpdateHandler.noncePrev"))) {
            if (_action.contains("Unsigned")) {
                update(ROUTER_UNSIGNED);
            } else {
                update(ROUTER_SIGNED);
            }
        }
    }

    private void update(UpdateType type) {
        ConsoleUpdateManager mgr = (ConsoleUpdateManager) _context.updateManager();
        if (mgr == null)
            return;
        if (mgr.isUpdateInProgress(ROUTER_SIGNED) || mgr.isUpdateInProgress(ROUTER_UNSIGNED)) {
            _log.error("Update already running");
            return;
        }
        mgr.update(type);
    }
}
