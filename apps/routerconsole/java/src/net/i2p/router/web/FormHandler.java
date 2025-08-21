package net.i2p.router.web;

import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.servlet.RequestWrapper;
import net.i2p.util.Log;

/**
 * Simple form handler base class - does not depend on servlets or jsp,
 * but instead the subclasses are populated with javabean properties.  e.g.
 * &lt;jsp:setProperty name="handler" property="*" /&gt;
 *
 * The form is "processed" after the properties are set and the first output
 * property is retrieved - either getAll(), getNotices() or getErrors().
 *
 * This Handler will only process a single POST. The jsp bean must be declared scope=request.
 *
 */
public abstract class FormHandler {
    protected RouterContext _context;
    protected Log _log;
    /** Not for multipart/form-data, will be null */
    @SuppressWarnings("rawtypes")
    protected Map _settings;
    /** Only for multipart/form-data. Warning, parameters are NOT XSS filtered */
    protected RequestWrapper _requestWrapper;
    private String _nonce, _nonce1, _nonce2;
    protected String _action;
    protected String _method;
    private final List<String> _errors;
    private final List<String> _notices;
    private boolean _processed;
    private boolean _valid;
    protected Writer _out;
    
    public FormHandler() {
        _errors = new ArrayList<String>();
        _notices = new ArrayList<String>();
        _valid = true;
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
            _log = _context.logManager().getLog(getClass());
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public void setNonce(String val) { _nonce = val == null ? null : DataHelper.stripHTML(val); }
    public void setAction(String val) { _action = val == null ? null : DataHelper.stripHTML(val); }

    /**
     * For many forms, it's easiest just to put all the parameters here.
     *
     * @since 0.9.4 consolidated from numerous FormHandlers
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void setSettings(Map settings) { _settings = new HashMap(settings); }

    /**
     *  Only set by formhandler.jsi for multipart/form-data
     *
     *  @since 0.9.19
     */
    public void setRequestWrapper(RequestWrapper rw) {
        _requestWrapper = rw;
    }

    /**
     *  Same as HelperBase
     *  @since 0.9.14.1
     */
    public boolean isAdvanced() {
        return _context.getBooleanProperty(HelperBase.PROP_ADVANCED);
    }

    /**
     * setSettings() must have been called previously
     * Curses Jetty for returning arrays.
     *
     * @since 0.9.4 consolidated from numerous FormHandlers
     * @return trimmed string or null
     */
    protected String getJettyString(String key) {
        if (_settings == null)
            return null;
        String[] arr = (String[]) _settings.get(key);
        if (arr == null)
            return null;
        return arr[0].trim();
    }

    /**
     * Call this to prevent changes using GET
     *
     * @param val the request method
     * @since 0.8.2
     */
    public void storeMethod(String val) { _method = val; }

    /**
     * @since 0.9.38
     */
    public void storeWriter(Writer out) { _out = out; }

    /**
     * The old nonces from the session
     * @since 0.9.4
     */
    public void storeNonces(String n1, String n2) {
        _nonce1 = n1;
        _nonce2 = n2;
    }
    
    /**
     * Implement this to perform the final processing (in turn, adding formNotice
     * and formError messages, etc)
     *
     * Will only be called if _action is non-null and the nonce is valid.
     */
    protected abstract void processForm();
    
    /**
     * Add an error message to display
     * Use if it does not include a link.
     * Escapes '&lt;' and '&gt;' before queueing
     */
    protected void addFormError(String errorMsg) {
        if (errorMsg == null) return;
        _errors.add(DataHelper.escapeHTML(errorMsg));
    }
    
    /**
     * Add a non-error message to display
     * Use if it does not include a link.
     * Escapes '&lt;' and '&gt;' before queueing
     */
    protected void addFormNotice(String msg) {
        if (msg == null) return;
        _notices.add(DataHelper.escapeHTML(msg));
    }
    
    /**
     * Add a non-error message to display
     * Use if it includes a link or other formatting.
     * Does not escape '&lt;' and '&gt;' before queueing
     * @since 0.9.14.1
     */
    protected void addFormNoticeNoEscape(String msg) {
        if (msg == null) return;
        _notices.add(msg);
    }
    
    /**
     * Add an error message to display
     * Use if it includes a link or other formatting.
     * Does not escape '&lt;' and '&gt;' before queueing
     * @since 0.9.19
     */
    protected void addFormErrorNoEscape(String msg) {
        if (msg == null) return;
        _errors.add(msg);
    }
    
    /**
     * Display everything, wrap it in a div for consistent presentation
     *
     */
    public String getAllMessages() { 
        validate();
        process();
        if (_errors.isEmpty() && _notices.isEmpty())
            return "";
        StringBuilder buf = new StringBuilder(512);
        buf.append("<div class=\"messages\" id=\"messages\">");
        if (!_errors.isEmpty()) {
            buf.append("<div class=\"error\">");
            buf.append(render(_errors));
            buf.append("</div>");
        }
        if (!_notices.isEmpty()) {
            buf.append("<div class=\"notice\">");
            buf.append(render(_notices));
            buf.append("</div>");
        }
        buf.append("</div>");
        return buf.toString();
    }
    
    /**
     * Display any error messages (processing the form if it hasn't 
     * been yet)
     *
     */
    public String getErrors() { 
        validate();
        process();
        return render(_errors);
    }
    
    /**
     * Display any non-error messages (processing the form if it hasn't 
     * been yet)
     *
     */
    public String getNotices() { 
        validate();
        process();
        return render(_notices);
    }
    
    /**
     * Make sure the nonce was set correctly, otherwise someone could just 
     * create a link like /confignet.jsp?hostname=localhost and break the
     * user's node (or worse).
     *
     */
    private void validate() {
        if (_processed) return;
        
        _valid = true;
        if (_action == null) {
            // not a form submit
            _valid = false;
            return;
        }
        // To prevent actions with GET, jsps must call storeMethod()
        if (_method != null && !"POST".equals(_method)) {
            addFormError("Invalid form submission, requires POST");
            _valid = false;
            return;
        }
        if (_nonce == null) {
            //addFormError("You trying to mess with me?  Huh?  Are you?");
            _valid = false;
            return;
        }
        
        String sharedNonce = CSSHelper.getNonce();
        if (sharedNonce.equals(_nonce)) {
            return;
        }
        
        if (!_nonce.equals(_nonce1) && !_nonce.equals(_nonce2)) {
                addFormError(_t("Invalid form submission, probably because you used the 'back' or 'reload' button on your browser. Please resubmit.")
                             + ' ' +
                             _t("If the problem persists, verify that you have cookies enabled in your browser."));
                _valid = false;
        }
    }
    
    private void process() {
        if (!_processed) {
            if (_valid)
                processForm();
            _processed = true;
        }
    }
    
    private static String render(List<String> source) {
        if (source.isEmpty()) {
            return "";
        } else {
            StringBuilder buf = new StringBuilder(512);
            buf.append("<ul>\n");
            for (int i = 0; i < source.size(); i++) {
                buf.append("<li>");
                buf.append(source.get(i));
                buf.append("</li>\n");
            }
            buf.append("</ul>\n");
            return buf.toString();
        }
    }
    
    /**
     *  Generate a new nonce.
     *  Only call once per page!
     *  @return a new random long as a String
     *  @since 0.8.5
     */
    public String getNewNonce() {
        String rv = Long.toString(_context.random().nextLong());
        return rv;
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

    /** two params @since 0.8.2 */
    public String _t(String s, Object o, Object o2) {
        return Messages.getString(s, o, o2, _context);
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
