package net.i2p.router.web;

import java.io.IOException;
import java.util.List;

import net.i2p.apps.systray.SysTray;
import net.i2p.apps.systray.UrlLauncher;
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
    
    @Override
    protected void processForm() {
        if (_action == null) return;
        
        if (_("Shutdown gracefully").equals(_action)) {
            _context.addShutdownTask(new UpdateWrapperManagerTask(Router.EXIT_GRACEFUL));
            _context.router().shutdownGracefully();
            addFormNotice(_("Graceful shutdown initiated"));
        } else if (_("Shutdown immediately").equals(_action)) {
            _context.addShutdownTask(new UpdateWrapperManagerTask(Router.EXIT_HARD));
            _context.router().shutdown(Router.EXIT_HARD);
            addFormNotice(_("Shutdown immediately!  boom bye bye bad bwoy"));
        } else if (_("Cancel graceful shutdown").equals(_action)) {
            _context.router().cancelGracefulShutdown();
            addFormNotice(_("Graceful shutdown cancelled"));
        } else if (_("Graceful restart").equals(_action)) {
            _context.addShutdownTask(new UpdateWrapperManagerTask(Router.EXIT_GRACEFUL_RESTART));
            _context.router().shutdownGracefully(Router.EXIT_GRACEFUL_RESTART);
            addFormNotice(_("Graceful restart requested"));
        } else if (_("Hard restart").equals(_action)) {
            _context.addShutdownTask(new UpdateWrapperManagerTask(Router.EXIT_HARD_RESTART));
            _context.router().shutdown(Router.EXIT_HARD_RESTART);
            addFormNotice(_("Hard restart requested"));
        } else if (_("Rekey and Restart").equals(_action)) {
            addFormNotice(_("Rekeying after graceful restart"));
            _context.addShutdownTask(new UpdateWrapperManagerAndRekeyTask(Router.EXIT_GRACEFUL_RESTART));
            _context.router().shutdownGracefully(Router.EXIT_GRACEFUL_RESTART);
        } else if (_("Rekey and Shutdown").equals(_action)) {
            addFormNotice(_("Rekeying after graceful shutdown"));
            _context.addShutdownTask(new UpdateWrapperManagerAndRekeyTask(Router.EXIT_GRACEFUL));
            _context.router().shutdownGracefully(Router.EXIT_GRACEFUL);
        } else if (_("Run I2P on startup").equals(_action)) {
            installService();
        } else if (_("Don't run I2P on startup").equals(_action)) {
            uninstallService();
        } else if (_("Dump threads").equals(_action)) {
            try {
                WrapperManager.requestThreadDump();
            } catch (Throwable t) {
                addFormError("Warning: unable to contact the service manager - " + t.getMessage());
            }
            addFormNotice("Threads dumped to wrapper.log");
        } else if (_("View console on startup").equals(_action)) {
            browseOnStartup(true);
            addFormNotice(_("Console is to be shown on startup"));
        } else if (_("Do not view console on startup").equals(_action)) {
            browseOnStartup(false);
            addFormNotice(_("Console is not to be shown on startup"));
        } else {
            //addFormNotice("Blah blah blah.  whatever.  I'm not going to " + _action);
        }
    }
    
    private void installService() {
        try { 
            Runtime.getRuntime().exec("install_i2p_service_winnt.bat");
            addFormNotice(_("Service installed"));
        } catch (IOException ioe) {
            addFormError(_("Warning: unable to install the service") + " - " + ioe.getMessage());
        }
    }
    private void uninstallService() {
        try { 
            Runtime.getRuntime().exec("uninstall_i2p_service_winnt.bat");
            addFormNotice(_("Service removed"));
        } catch (IOException ioe) {
            addFormError(_("Warning: unable to remove the service") + " - " + ioe.getMessage());
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
