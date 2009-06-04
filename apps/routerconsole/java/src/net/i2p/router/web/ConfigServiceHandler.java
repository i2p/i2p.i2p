package net.i2p.router.web;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

import net.i2p.apps.systray.SysTray;
import net.i2p.apps.systray.UrlLauncher;
import net.i2p.data.DataHelper;
import net.i2p.router.Router;
import net.i2p.router.startup.ClientAppConfig;

import org.tanukisoftware.wrapper.WrapperManager;

/**
 * Handler to deal with form submissions from the service config form and act
 * upon the values.
 *
 */
public class ConfigServiceHandler extends FormHandler {
    
    public static class UpdateWrapperManagerTask implements Runnable {
        private int _exitCode;
        public UpdateWrapperManagerTask(int exitCode) {
            _exitCode = exitCode;
        }
        public void run() {
            try {
                WrapperManager.signalStopped(_exitCode);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    public static class UpdateWrapperManagerAndRekeyTask implements Runnable {
        private int _exitCode;
        public UpdateWrapperManagerAndRekeyTask(int exitCode) {
            _exitCode = exitCode;
        }
        public void run() {
            try {
                ContextHelper.getContext(null).router().killKeys();
                WrapperManager.signalStopped(_exitCode);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }
    
    protected void processForm() {
        if (_action == null) return;
        
        if ("Shutdown gracefully".equals(_action)) {
            _context.addShutdownTask(new UpdateWrapperManagerTask(Router.EXIT_GRACEFUL));
            _context.router().shutdownGracefully();
            addFormNotice("Graceful shutdown initiated");
        } else if ("Shutdown immediately".equals(_action)) {
            _context.addShutdownTask(new UpdateWrapperManagerTask(Router.EXIT_HARD));
            _context.router().shutdown(Router.EXIT_HARD);
            addFormNotice("Shutdown immediately!  boom bye bye bad bwoy");
        } else if ("Cancel graceful shutdown".equals(_action)) {
            _context.router().cancelGracefulShutdown();
            addFormNotice("Graceful shutdown cancelled");
        } else if ("Graceful restart".equals(_action)) {
            _context.addShutdownTask(new UpdateWrapperManagerTask(Router.EXIT_GRACEFUL_RESTART));
            _context.router().shutdownGracefully(Router.EXIT_GRACEFUL_RESTART);
            addFormNotice("Graceful restart requested");
        } else if ("Hard restart".equals(_action)) {
            _context.addShutdownTask(new UpdateWrapperManagerTask(Router.EXIT_HARD_RESTART));
            _context.router().shutdown(Router.EXIT_HARD_RESTART);
            addFormNotice("Hard restart requested");
        } else if ("Rekey and Restart".equals(_action)) {
            addFormNotice("Rekeying after graceful restart");
            _context.addShutdownTask(new UpdateWrapperManagerAndRekeyTask(Router.EXIT_GRACEFUL_RESTART));
            _context.router().shutdownGracefully(Router.EXIT_GRACEFUL_RESTART);
        } else if ("Rekey and Shutdown".equals(_action)) {
            addFormNotice("Rekeying after graceful shutdown");
            _context.addShutdownTask(new UpdateWrapperManagerAndRekeyTask(Router.EXIT_GRACEFUL));
            _context.router().shutdownGracefully(Router.EXIT_GRACEFUL);
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
        } else if ("View console on startup".equals(_action)) {
            browseOnStartup(true);
            addFormNotice("Console is to be shown on startup");
        } else if ("Do not view console on startup".equals(_action)) {
            browseOnStartup(false);
            addFormNotice("Console is not to be shown on startup");
        } else {
            //addFormNotice("Blah blah blah.  whatever.  I'm not going to " + _action);
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

    private void browseOnStartup(boolean shouldLaunchBrowser) {
        List clients = ClientAppConfig.getClientApps(_context);
        boolean found = false;
        for (int cur = 0; cur < clients.size(); cur++) {
            ClientAppConfig ca = (ClientAppConfig) clients.get(cur);
            if (UrlLauncher.class.getName().equals(ca.className)) {
                ca.disabled = !shouldLaunchBrowser;
                found = true;
                break;
            }
        }
        // releases <= 0.6.5 deleted the entry completely
        if (shouldLaunchBrowser && !found) {
            ClientAppConfig ca = new ClientAppConfig(UrlLauncher.class.getName(), "consoleBrowser", "http://127.0.0.1:7657", 5, false);
            clients.add(ca);
        }
        ClientAppConfig.writeClientAppConfig(_context, clients);
    }
}
