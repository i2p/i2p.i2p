package net.i2p.router.web;

import java.io.Writer;

import net.i2p.router.RouterContext;

/**
 * Base helper
 */
public abstract class HelperBase {
    protected RouterContext _context;
    protected Writer _out;

    /** @since public since 0.9.33, was package private */
    public static final String PROP_ADVANCED = "routerconsole.advanced";
    /** @since public since 0.9.33, was package private */
    public static final String CHECKED = " checked=\"checked\" ";

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

    /** @since 0.9.9 */
    public boolean isAdvanced() {
        return _context.getBooleanProperty(PROP_ADVANCED);
    }

    /** might be useful in the jsp's */
    //public RouterContext getContext() { return _context; }


    /**
     *  Renamed from setWriter, we realy don't want setFoo(non-String)
     *  Prevent jsp.error.beans.property.conversion 500 error for ?writer=foo
     *  @since 0.8.2
     */
    public void storeWriter(Writer out) { _out = out; }

    /**
     *  Is a boolean property set to true?
     *
     *  @param prop must default to false
     *  @return non-null, either "" or " checked=\"checked\" "
     *  @since 0.9.24 consolidated from various helpers
     */
    protected String getChecked(String prop) {
        if (_context.getBooleanProperty(prop))
            return CHECKED;
        return "";
    }

    /** translate a string */
    public String _t(String s) {
        return Messages.getString(s, _context);
    }

    /**
     *  translate a string with a parameter
     *  This is a lot more expensive than _t(s), so use sparingly.
     *
     *  @param s string to be translated containing {0}
     *    The {0} will be replaced by the parameter.
     *    Single quotes must be doubled, i.e. ' -&gt; '' in the string.
     *  @param o parameter, not translated.
     *    To translate parameter also, use _t("foo {0} bar", _t("baz"))
     *    Do not double the single quotes in the parameter.
     *    Use autoboxing to call with ints, longs, floats, etc.
     */
    public String _t(String s, Object o) {
        return Messages.getString(s, o, _context);
    }

    /** two params @since 0.7.14 */
    public String _t(String s, Object o, Object o2) {
        return Messages.getString(s, o, o2, _context);
    }

    /** translate (ngettext) @since 0.7.14 */
    public String ngettext(String s, String p, int n) {
        return Messages.getString(n, s, p, _context);
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
