package net.i2p.router.web;

import java.util.List;
import java.util.ArrayList;

import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Simple form handler base class - does not depend on servlets or jsp,
 * but instead the subclasses are populated with javabean properties.  e.g.
 * <jsp:setProperty name="handler" property="*" />
 *
 * The form is "processed" after the properties are set and the first output
 * property is retrieved - either getNotices() or getErrors().
 *
 */
public class FormHandler {
    protected RouterContext _context;
    protected Log _log;
    private String _nonce;
    protected String _action;
    protected String _passphrase;
    private List _errors;
    private List _notices;
    private boolean _processed;
    private boolean _valid;
    
    public FormHandler() {
        _errors = new ArrayList();
        _notices = new ArrayList();
        _action = null;
        _processed = false;
        _valid = true;
        _nonce = null;
        _passphrase = null;
    }
    
    /**
     * Configure this bean to query a particular router context
     *
     * @param contextId begging few characters of the routerHash, or null to pick
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
     * Display any error messages (processing the form if it hasn't 
     * been yet)
     *
     */
    public String getErrors() { 
        validate();
        return render(_errors);
    }
    
    /**
     * Display any non-error messages (processing the form if it hasn't 
     * been yet)
     *
     */
    public String getNotices() { 
        validate();
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
            addFormError("You trying to mess with me?  Huh?  Are you?");
            _valid = false;
            return;
        }
        String nonce = System.getProperty(getClass().getName() + ".nonce");
        String noncePrev = System.getProperty(getClass().getName() + ".noncePrev");
        if ( ( (nonce == null) || (!_nonce.equals(nonce)) ) &&
             ( (noncePrev == null) || (!_nonce.equals(noncePrev)) ) ) {
                 
            String expected = _context.getProperty("consolePassword");
            if ( (expected != null) && (expected.trim().length() > 0) && (expected.equals(_passphrase)) ) {
                // ok
            } else {
                addFormError("Invalid nonce, are you being spoofed?");
                _valid = false;
            }
        }
    }
    
    private String render(List source) {
        if (!_processed) {
            if (_valid)
                processForm();
            _processed = true;
        }
        if (source.size() <= 0) {
            return "";
        } else if (source.size() == 1) {
            return (String)source.get(0);
        } else {
            StringBuffer buf = new StringBuffer(512);
            buf.append("<ul>\n");
            for (int i = 0; i < source.size(); i++) {
                buf.append("<li>");
                buf.append((String)source.get(i));
                buf.append("</li>\n");
            }
            buf.append("</ul>\n");
            return buf.toString();
        }
    }
    
}
