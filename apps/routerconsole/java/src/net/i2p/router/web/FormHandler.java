package net.i2p.router.web;

import java.util.List;
import java.util.ArrayList;

import net.i2p.util.Log;

import net.i2p.router.RouterContext;
import net.i2p.router.ClientTunnelSettings;

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
    private List _errors;
    private List _notices;
    private boolean _processed;
    
    public FormHandler() {
        _errors = new ArrayList();
        _notices = new ArrayList();
        _processed = false;
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
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

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
        return render(_errors);
    }
    
    /**
     * Display any non-error messages (processing the form if it hasn't 
     * been yet)
     *
     */
    public String getNotices() { 
        return render(_notices);
    }
    
    private String render(List source) {
        if (!_processed) {
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
