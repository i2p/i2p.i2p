package net.i2p.router.web;

import java.io.IOException;

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
            try { 
                WrapperManager.signalStopped(Router.EXIT_GRACEFUL);
            } catch (Throwable t) {
                addFormError("Warning: unable to contact the service manager - " + t.getMessage());
            }
            _context.router().shutdownGracefully();
            addFormNotice("Graceful shutdown initiated");
        } else if ("Shutdown immediately".equals(_action)) {
            try {
                WrapperManager.signalStopped(Router.EXIT_HARD);
            } catch (Throwable t) {
                addFormError("Warning: unable to contact the service manager - " + t.getMessage());
            }
            _context.router().shutdown(Router.EXIT_HARD);
            addFormNotice("Shutdown immediately!  boom bye bye bad bwoy");
        } else if ("Cancel graceful shutdown".equals(_action)) {
            _context.router().cancelGracefulShutdown();
            addFormNotice("Graceful shutdown cancelled");
        } else if ("Hard restart".equals(_action)) {
            _context.router().shutdown(Router.EXIT_HARD_RESTART);
            addFormNotice("Hard restart requested");
        } else if ("Run I2P on startup".equals(_action)) {
            installService();
        } else if ("Don't run I2P on startup".equals(_action)) {
            uninstallService();
        } else if ("Dump threads".equals(_action)) {
            try {
                WrapperManager.requestThreadDump();
            } catch (Throwable t) {
                addFormError("Warning: unable to contact the service manager - " + t.getMessage());
            }
            addFormNotice("Threads dumped to wrapper.log");
        } else if ("Show systray icon".equals(_action)) {
            try {
                SysTray tray = SysTray.getInstance();
                if (tray != null) {
                    tray.show();
                    addFormNotice("Systray enabled");
                } else {
                    addFormNotice("Systray not supported on this platform");
                }
            } catch (Throwable t) {
                addFormError("Warning: unable to contact the systray manager - " + t.getMessage());
            }
        } else if ("Hide systray icon".equals(_action)) {
            try {
                SysTray tray = SysTray.getInstance();
                if (tray != null) {
                    tray.hide();
                    addFormNotice("Systray disabled");
                } else {
                    addFormNotice("Systray not supported on this platform");
                }
            } catch (Throwable t) {
                addFormError("Warning: unable to contact the systray manager - " + t.getMessage());
            }
        } else {
            addFormNotice("Blah blah blah.  whatever.  I'm not going to " + _action);
        }
    }
    
    private void installService() {
        try { 
            Runtime.getRuntime().exec("install_i2p_service_winnt.bat");
            addFormNotice("Service installed");
        } catch (IOException ioe) {
            addFormError("Warning: unable to install the service - " + ioe.getMessage());
        }
    }
    private void uninstallService() {
        try { 
            Runtime.getRuntime().exec("uninstall_i2p_service_winnt.bat");
            addFormNotice("Service removed");
        } catch (IOException ioe) {
            addFormError("Warning: unable to remove the service - " + ioe.getMessage());
        }
    }
}