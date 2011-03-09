package net.i2p.router.web;

import java.util.ArrayList;
import java.util.List;

import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Simple form handler base class - does not depend on servlets or jsp,
 * but instead the subclasses are populated with javabean properties.  e.g.
 * <jsp:setProperty name="handler" property="*" />
 *
 * The form is "processed" after the properties are set and the first output
 * property is retrieved - either getAll(), getNotices() or getErrors().
 *
 */
public class FormHandler {
    protected RouterContext _context;
    protected Log _log;
    private String _nonce;
    protected String _action;
    protected String _method;
    protected String _passphrase;
    private final List<String> _errors;
    private final List<String> _notices;
    private boolean _processed;
    private boolean _valid;
    private static final String NONCE_SUFFIX = ".nonce";
    private static final String PREV_SUFFIX = "Prev";
    
    public FormHandler() {
        _errors = new ArrayList();
        _notices = new ArrayList();
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

    public void setNonce(String val) { _nonce = val; }
    public void setAction(String val) { _action = val; }
    public void setPassphrase(String val) { _passphrase = val; }

    /**
     * Call this to prevent changes using GET
     *
     * @param val the request method
     * @since 0.8.2
     */
    public void storeMethod(String val) { _method = val; }
    
    /**
     * Override this to perform the final processing (in turn, adding formNotice
     * and formError messages, etc)
     *
     */
    protected void processForm() {}
    
    /**
     * Add an error message to display
     */
    protected void addFormError(String errorMsg) {
        if (errorMsg == null) return;
        _errors.add(errorMsg);
    }
    
    /**
     * Add a non-error message to display
     */
    protected void addFormNotice(String msg) {
        if (msg == null) return;
        _notices.add(msg);
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
        buf.append("<div class=\"messages\" id=\"messages\"><p>");
        if (!_errors.isEmpty()) {
            buf.append("<span class=\"error\">");
            buf.append(render(_errors));
            buf.append("</span>");
        }
        if (!_notices.isEmpty()) {
            buf.append("<span class=\"notice\">");
            buf.append(render(_notices));
            buf.append("</span>");
        }
        buf.append("</p></div>");
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
        if (_nonce == null) {
            //addFormError("You trying to mess with me?  Huh?  Are you?");
            _valid = false;
            return;
        }
        // To prevent actions with GET, jsps must call storeMethod()
        if (_method != null && !"POST".equals(_method)) {
            addFormError("Invalid form submission, requires POST not " + _method);
            _valid = false;
            return;
        }
        
        String sharedNonce = System.getProperty("router.consoleNonce");
        if ( (sharedNonce != null) && (sharedNonce.equals(_nonce) ) ) {
            return;
        }
        
        String nonce = System.getProperty(getClass().getName() + NONCE_SUFFIX);
        String noncePrev = nonce + PREV_SUFFIX;
        if ( ( (nonce == null) || (!_nonce.equals(nonce)) ) &&
             ( (noncePrev == null) || (!_nonce.equals(noncePrev)) ) ) {
                 
            String expected = _context.getProperty("consolePassword");
            if ( (expected != null) && (expected.trim().length() > 0) && (expected.equals(_passphrase)) ) {
                // ok
            } else {
                addFormError(_("Invalid form submission, probably because you used the 'back' or 'reload' button on your browser. Please resubmit."));
                _valid = false;
            }
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
     *  Generate a new nonce, store old and new in the system properties.
     *  Only call once per page!
     *  @return a new random long as a String
     *  @since 0.8.5
     */
    public String getNewNonce() {
        String prop = getClass().getName() + NONCE_SUFFIX;
        String prev = System.getProperty(prop);
        if (prev != null)
            System.setProperty(prop + PREV_SUFFIX, prev);
        String rv = Long.toString(_context.random().nextLong());
        System.setProperty(prop, rv);
        return rv;
    }

    /** translate a string */
    public String _(String s) {
        return Messages.getString(s, _context);
    }

    /**
     *  translate a string with a parameter
     *  This is a lot more expensive than _(s), so use sparingly.
     *
     *  @param s string to be translated containing {0}
     *    The {0} will be replaced by the parameter.
     *    Single quotes must be doubled, i.e. ' -> '' in the string.
     *  @param o parameter, not translated.
     *    To tranlslate parameter also, use _("foo {0} bar", _("baz"))
     *    Do not double the single quotes in the parameter.
     *    Use autoboxing to call with ints, longs, floats, etc.
     */
    public String _(String s, Object o) {
        return Messages.getString(s, o, _context);
    }

    /** two params @since 0.8.2 */
    public String _(String s, Object o, Object o2) {
        return Messages.getString(s, o, o2, _context);
    }
}
