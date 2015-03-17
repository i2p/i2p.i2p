package net.i2p.router.web;

import java.io.File;
import java.io.IOException;
import java.util.List;

import net.i2p.apps.systray.UrlLauncher;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.startup.ClientAppConfig;
import net.i2p.util.VersionComparator;

import org.tanukisoftware.wrapper.WrapperManager;

/**
 * Handler to deal with form submissions from the service config form and act
 * upon the values.
 *
 */
public class ConfigServiceHandler extends FormHandler {
    
    private static WrapperListener _wrapperListener;

    private static final String LISTENER_AVAILABLE = "3.2.0";

    /**
     *  Register two shutdown hooks, one to rekey and/or tell the wrapper we are stopping,
     *  and a final one to tell the wrapper we are stopped.
     *
     *  @since 0.8.8
     */
    private void registerWrapperNotifier(int code, boolean rekey) {
        registerWrapperNotifier(_context, code, rekey);
    }
    
    /**
     *  Register two shutdown hooks, one to rekey and/or tell the wrapper we are stopping,
     *  and a final one to tell the wrapper we are stopped.
     *
     *  @since 0.8.8
     */
    public static void registerWrapperNotifier(RouterContext ctx, int code, boolean rekey) {
        Runnable task = new UpdateWrapperOrRekeyTask(rekey, ctx.hasWrapper());
        ctx.addShutdownTask(task);
        if (ctx.hasWrapper()) {
            task = new FinalWrapperTask(code);
            ctx.addFinalShutdownTask(task);
        }
    }

    /**
     *  Rekey and/or tell the wrapper we are stopping,
     */
    private static class UpdateWrapperOrRekeyTask implements Runnable {
        private final boolean _rekey;
        private final boolean _tellWrapper;
        private static final int HASHCODE = -123999871;
        private static final int WAIT = 30*1000;

        public UpdateWrapperOrRekeyTask(boolean rekey, boolean tellWrapper) {
            _rekey = rekey;
            _tellWrapper = tellWrapper;
        }

        public void run() {
            try {
                if (_rekey)
                    ContextHelper.getContext(null).router().killKeys();
                if (_tellWrapper)
                    WrapperManager.signalStopping(WAIT);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }

        /**
         *  Make them all look the same since the hooks are stored in a set
         *  and we don't want dups
         */
        @Override
        public int hashCode() {
            return HASHCODE;
        }

        /**
         *  Make them all look the same since the hooks are stored in a set
         *  and we don't want dups
         */
        @Override
        public boolean equals(Object o) {
            return (o != null) && (o instanceof UpdateWrapperOrRekeyTask);
        }
    }

    /**
     *  Tell the wrapper we are stopped.
     *
     *  @since 0.8.8
     */
    private static class FinalWrapperTask implements Runnable {
        private final int _exitCode;
        private static final int HASHCODE = 123999871;

        public FinalWrapperTask(int exitCode) {
            _exitCode = exitCode;
        }

        public void run() {
            try {
                WrapperManager.signalStopped(_exitCode);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }

        /**
         *  Make them all look the same since the hooks are stored in a set
         *  and we don't want dups
         */
        @Override
        public int hashCode() {
            return HASHCODE;
        }

        /**
         *  Make them all look the same since the hooks are stored in a set
         *  and we don't want dups
         */
        @Override
        public boolean equals(Object o) {
            return (o != null) && (o instanceof FinalWrapperTask);
        }
    }

    /**
     *  Register a handler for signals,
     *  so we can handle HUP from the wrapper (non-Windows only, wrapper 3.2.0 or higher)
     *
     *  @since 0.8.13
     */
    synchronized static void registerSignalHandler(RouterContext ctx) {
        if (ctx.hasWrapper() && _wrapperListener == null &&
            !System.getProperty("os.name").startsWith("Win")) {
            String wv = System.getProperty("wrapper.version");
            if (wv != null && (new VersionComparator()).compare(wv, LISTENER_AVAILABLE) >= 0) {
                try {
                   _wrapperListener = new WrapperListener(ctx);
                } catch (Throwable t) {}
            }
        }
    }

    /**
     *  Unregister the handler for signals
     *
     *  @since 0.8.13
     */
    public synchronized static void unregisterSignalHandler() {
        if (_wrapperListener != null) {
            _wrapperListener.unregister();
            _wrapperListener = null;
        }
    }

    @Override
    protected void processForm() {
        if (_action == null) return;
        
        if (_("Shutdown gracefully").equals(_action)) {
            if (_context.hasWrapper())
                registerWrapperNotifier(Router.EXIT_GRACEFUL, false);
            _context.router().shutdownGracefully();
            addFormNotice(_("Graceful shutdown initiated"));
        } else if (_("Shutdown immediately").equals(_action)) {
            if (_context.hasWrapper())
                registerWrapperNotifier(Router.EXIT_HARD, false);
            _context.router().shutdown(Router.EXIT_HARD);
            addFormNotice(_("Shutdown immediately!  boom bye bye bad bwoy"));
        } else if (_("Cancel graceful shutdown").equals(_action)) {
            _context.router().cancelGracefulShutdown();
            addFormNotice(_("Graceful shutdown cancelled"));
        } else if (_("Graceful restart").equals(_action)) {
            // should have wrapper if restart button is visible
            if (_context.hasWrapper())
                registerWrapperNotifier(Router.EXIT_GRACEFUL_RESTART, false);
            _context.router().shutdownGracefully(Router.EXIT_GRACEFUL_RESTART);
            addFormNotice(_("Graceful restart requested"));
        } else if (_("Hard restart").equals(_action)) {
            // should have wrapper if restart button is visible
            if (_context.hasWrapper())
                registerWrapperNotifier(Router.EXIT_HARD_RESTART, false);
            _context.router().shutdown(Router.EXIT_HARD_RESTART);
            addFormNotice(_("Hard restart requested"));
        } else if (_("Rekey and Restart").equals(_action)) {
            addFormNotice(_("Rekeying after graceful restart"));
            registerWrapperNotifier(Router.EXIT_GRACEFUL_RESTART, true);
            _context.router().shutdownGracefully(Router.EXIT_GRACEFUL_RESTART);
        } else if (_("Rekey and Shutdown").equals(_action)) {
            addFormNotice(_("Rekeying after graceful shutdown"));
            registerWrapperNotifier(Router.EXIT_GRACEFUL, true);
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
            File wlog = LogsHelper.wrapperLogFile(_context);
            addFormNotice(_("Threads dumped to {0}", wlog.getAbsolutePath()));
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
