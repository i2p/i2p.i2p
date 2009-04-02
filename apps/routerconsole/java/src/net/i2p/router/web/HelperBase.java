package net.i2p.router.web;

import java.io.Writer;

import net.i2p.router.RouterContext;

/**
 * Base helper
 */
public abstract class HelperBase {
    protected RouterContext _context;
    protected Writer _out;

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

    public void setWriter(Writer out) { _out = out; }
}
