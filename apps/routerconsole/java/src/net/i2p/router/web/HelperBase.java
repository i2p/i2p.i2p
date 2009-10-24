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
     * @param contextId beginning few characters of the routerHash, or null to pick
     *                  the first one we come across.
     */
    public void setContextId(String contextId) {
        try {
            _context = ContextHelper.getContext(contextId);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /** might be useful in the jsp's */
    //public RouterContext getContext() { return _context; }

    public void setWriter(Writer out) { _out = out; }

    /** translate a string */
    public String _(String s) {
        return Messages.getString(s, _context);
    }

    /**
     *  Mark a string for extraction by xgettext and translation.
     *  Use this only in static initializers.
     *  It does not translate!
     *  @return s
     */
    public static String _x(String s) {
        return s;
    }
}
