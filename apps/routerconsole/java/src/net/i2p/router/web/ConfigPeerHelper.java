package net.i2p.router.web;

import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;

public class ConfigPeerHelper {
    private RouterContext _context;
    /**
     * Configure this bean to query a particular router context
     *
     * @param contextId begging few characters of the routerHash, or null to pick
     *                  the first one we come across.
     */
    public void setContextId(String contextId) {
        try {
            _context = ContextHelper.getContext(contextId);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public ConfigPeerHelper() {}
    
}
