package net.i2p.router.web;

import net.i2p.router.ClientTunnelSettings;

/**
 * Handler to deal with form submissions from the service config form and act
 * upon the values.
 *
 */
public class ConfigServiceHandler extends FormHandler {
    private String _action;
    private String _nonce;
    
    public void ConfigNetHandler() {
        _action = null;
        _nonce = null;
    }
    
    protected void processForm() {
        if (_action == null) return;
        if (_nonce == null) {
            addFormError("You trying to mess with me?  Huh?  Are you?");
            return;
        }
        String nonce = System.getProperty(ConfigServiceHandler.class.getName() + ".nonce");
        String noncePrev = System.getProperty(ConfigServiceHandler.class.getName() + ".noncePrev");
        if ( (!_nonce.equals(nonce)) && (!_nonce.equals(noncePrev)) ) {
            addFormError("Invalid nonce?  Hmmm, someone is spoofing you.  prev=["+ noncePrev + "] nonce=[" + nonce + "] param=[" + _nonce + "]");
            return;
        }
        if ("Shutdown gracefully".equals(_action)) {
            _context.router().shutdownGracefully();
            addFormNotice("Graceful shutdown initiated");
        } else if ("Shutdown immediately".equals(_action)) {
            _context.router().shutdown();
            addFormNotice("Shutdown immediately!  boom bye bye bad bwoy");
        } else if ("Cancel graceful shutdown".equals(_action)) {
            _context.router().cancelGracefulShutdown();
            addFormNotice("Graceful shutdown cancelled");
        } else {
            addFormNotice("Blah blah blah.  whatever.  I'm not going to " + _action);
        }
    }
    public void setAction(String action) { _action = action; }
    public void setNonce(String nonce) { _nonce = nonce; }
}
