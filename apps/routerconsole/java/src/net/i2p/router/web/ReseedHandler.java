package net.i2p.router.web;

import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.reseed.Reseeder;

/**
 * Handler to deal with reseed requests.
 */
public class ReseedHandler extends HelperBase {
    private static Reseeder _reseedRunner;

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
    
    public void requestReseed() {
        synchronized (ReseedHandler.class) {
            if (_reseedRunner == null)
                _reseedRunner = new Reseeder(_context);
            _reseedRunner.requestReseed();
        }
    }
}
