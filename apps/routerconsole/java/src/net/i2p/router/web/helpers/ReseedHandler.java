package net.i2p.router.web.helpers;

import net.i2p.router.RouterContext;
import net.i2p.router.web.ContextHelper;
import net.i2p.router.web.HelperBase;

/**
 * Handler to deal with reseed requests.
 */
public class ReseedHandler extends HelperBase {
    public ReseedHandler() {
        this(ContextHelper.getContext(null));
    }
    public ReseedHandler(RouterContext ctx) {
        _context = ctx;
    }

    public void setReseedNonce(String nonce) { 
        if (nonce == null) return;
        if (nonce.equals(System.getProperty("net.i2p.router.web.ReseedHandler.nonce")) ||
            nonce.equals(System.getProperty("net.i2p.router.web.ReseedHandler.noncePrev"))) {
            requestReseed();
        }
    }
    
    private void requestReseed() {
        _context.netDb().reseedChecker().requestReseed();
    }
}
