package net.i2p.router.web;

import net.i2p.router.ClientTunnelSettings;
import net.i2p.router.Router;

import net.i2p.apps.systray.SysTray;
import org.tanukisoftware.wrapper.WrapperManager;

/**
 * Handler to deal with form submissions from the service config form and act
 * upon the values.
 *
 */
public class ConfigServiceHandler extends FormHandler {
    public void ConfigNetHandler() {}
    
    protected void processForm() {
        if (_action == null) return;
        
        if ("Shutdown gracefully".equals(_action)) {
            WrapperManager.signalStopped(Router.EXIT_GRACEFUL);
            _context.router().shutdownGracefully();
            addFormNotice("Graceful shutdown initiated");
        } else if ("Shutdown immediately".equals(_action)) {
            WrapperManager.signalStopped(Router.EXIT_HARD);
            _context.router().shutdown(Router.EXIT_HARD);
            addFormNotice("Shutdown immediately!  boom bye bye bad bwoy");
        } else if ("Cancel graceful shutdown".equals(_action)) {
            _context.router().cancelGracefulShutdown();
            addFormNotice("Graceful shutdown cancelled");
        } else if ("Hard restart".equals(_action)) {
            _context.router().shutdown(Router.EXIT_HARD_RESTART);
            addFormNotice("Hard restart requested");
        } else if ("Dump threads".equals(_action)) {
            WrapperManager.requestThreadDump();
            addFormNotice("Threads dumped to logs/wrapper.log");
        } else if ("Show systray icon".equals(_action)) {
            SysTray tray = SysTray.getInstance();
            if (tray != null) {
                tray.show();
                addFormNotice("Systray enabled");
            } else {
                addFormNotice("Systray not supported on this platform");
            }
        } else if ("Hide systray icon".equals(_action)) {
            SysTray tray = SysTray.getInstance();
            if (tray != null) {
                tray.hide();
                addFormNotice("Systray disabled");
            } else {
                addFormNotice("Systray not supported on this platform");
            }
        } else {
            addFormNotice("Blah blah blah.  whatever.  I'm not going to " + _action);
        }
    }
}
