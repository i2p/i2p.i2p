package net.i2p.router.web;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Iterator;
import java.util.Properties;
import java.util.TreeMap;

import net.i2p.data.DataHelper;
import net.i2p.router.ClientTunnelSettings;
import net.i2p.router.Router;
import net.i2p.apps.systray.SysTray;
import net.i2p.apps.systray.UrlLauncher;
import org.tanukisoftware.wrapper.WrapperManager;

/**
 * Handler to deal with form submissions from the service config form and act
 * upon the values.
 *
 */
public class ConfigServiceHandler extends FormHandler {
    public void ConfigNetHandler() {}
    
    private class UpdateWrapperManagerTask implements Runnable {
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
    
    protected void processForm() {
        if (_action == null) return;
        
        if ("Shutdown gracefully".equals(_action)) {
            _context.router().addShutdownTask(new UpdateWrapperManagerTask(Router.EXIT_GRACEFUL));
            _context.router().shutdownGracefully();
            addFormNotice("Graceful shutdown initiated");
        } else if ("Shutdown immediately".equals(_action)) {
            _context.router().addShutdownTask(new UpdateWrapperManagerTask(Router.EXIT_HARD));
            _context.router().shutdown(Router.EXIT_HARD);
            addFormNotice("Shutdown immediately!  boom bye bye bad bwoy");
        } else if ("Cancel graceful shutdown".equals(_action)) {
            _context.router().cancelGracefulShutdown();
            addFormNotice("Graceful shutdown cancelled");
        } else if ("Graceful restart".equals(_action)) {
            _context.router().addShutdownTask(new UpdateWrapperManagerTask(Router.EXIT_GRACEFUL_RESTART));
            _context.router().shutdownGracefully(Router.EXIT_GRACEFUL_RESTART);
            addFormNotice("Graceful restart requested");
        } else if ("Hard restart".equals(_action)) {
            _context.router().addShutdownTask(new UpdateWrapperManagerTask(Router.EXIT_HARD_RESTART));
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
        } else if ("View console on startup".equals(_action)) {
            browseOnStartup(true);
            addFormNotice("Console is to be shown on startup");
        } else if ("Do not view console on startup".equals(_action)) {
            browseOnStartup(false);
            addFormNotice("Console is not to be shown on startup");
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

    private final static String NL = System.getProperty("line.separator");
    private void browseOnStartup(boolean shouldLaunchBrowser) {
        File f = new File("clients.config");
        Properties p = new Properties();
        try {
            DataHelper.loadProps(p, f);
            
            int i = 0;
            int launchIndex = -1;
            while (true) {
                String className = p.getProperty("clientApp." + i + ".main");
                if (className == null) break;
                if (UrlLauncher.class.getName().equals(className)) {
                    launchIndex = i;
                    break;
                }
                i++;
            }
            
            if ((launchIndex >= 0) && shouldLaunchBrowser)
                return;
            if ((launchIndex < 0) && !shouldLaunchBrowser)
                return;
            
            if (shouldLaunchBrowser) {
                p.setProperty("clientApp." + i + ".main", UrlLauncher.class.getName());
                p.setProperty("clientApp." + i + ".name", "BrowserLauncher");
                p.setProperty("clientApp." + i + ".args", "http://localhost:7657/index.jsp");
                p.setProperty("clientApp." + i + ".delay", "5");
            } else {
                p.remove("clientApp." + launchIndex + ".main");
                p.remove("clientApp." + launchIndex + ".name");
                p.remove("clientApp." + launchIndex + ".args");
                p.remove("clientApp." + launchIndex + ".onBoot");
                p.remove("clientApp." + launchIndex + ".delay");

                i = launchIndex + 1;
                while (true) {
                    String main = p.getProperty("clientApp." + i + ".main");
                    String name = p.getProperty("clientApp." + i + ".name");
                    String args = p.getProperty("clientApp." + i + ".args");
                    String boot = p.getProperty("clientApp." + i + ".onBoot");
                    String delay= p.getProperty("clientApp." + i + ".delay");

                    if (main == null) break;

                    p.setProperty("clientApp." + (i-1) + ".main", main);
                    p.setProperty("clientApp." + (i-1) + ".name", name);
                    p.setProperty("clientApp." + (i-1) + ".args", args);
                    if (boot != null)
                        p.setProperty("clientApp." + (i-1) + ".onBoot", boot);
                    if (delay != null)
                        p.setProperty("clientApp." + (i-1) + ".delay", delay);

                    p.remove("clientApp." + i + ".main");
                    p.remove("clientApp." + i + ".name");
                    p.remove("clientApp." + i + ".args");
                    p.remove("clientApp." + i + ".onBoot");
                    p.remove("clientApp." + i + ".delay");

                    i++;
                }
            }
            
            TreeMap sorted = new TreeMap(p);
            FileWriter out = new FileWriter(f);
            for (Iterator iter = sorted.keySet().iterator(); iter.hasNext(); ) {
                String name = (String)iter.next();
                String val = (String)sorted.get(name);
                out.write(name + "=" + val + NL);
            }
            out.close();
        } catch (IOException ioe) {
            addFormError("Error updating the client config");
        }
    }
}