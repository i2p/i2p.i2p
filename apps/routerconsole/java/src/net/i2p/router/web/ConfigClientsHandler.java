package net.i2p.router.web;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.i2p.router.startup.ClientAppConfig;
import net.i2p.router.startup.LoadClientAppsJob;
import net.i2p.util.Log;

import org.mortbay.http.HttpListener;
import org.mortbay.jetty.Server;

/**
 *  Saves changes to clients.config or webapps.config
 */
public class ConfigClientsHandler extends FormHandler {
    private Log _log;
    private Map _settings;
    
    public ConfigClientsHandler() {
        _log = ContextHelper.getContext(null).logManager().getLog(ConfigClientsHandler.class);
    }

    protected void processForm() {
        if (_action.startsWith("Save Client")) {
            saveClientChanges();
        } else if (_action.startsWith("Save WebApp")) {
            saveWebAppChanges();
        } else if (_action.startsWith("Start ")) {
            String app = _action.substring(6);
            int appnum = -1;
            try {
                appnum = Integer.parseInt(app);
            } catch (NumberFormatException nfe) {}
            if (appnum >= 0)
                startClient(appnum);
            else
                startWebApp(app);
        } else if (_action.toLowerCase().startsWith("start<span class=hide> ") &&
                   _action.toLowerCase().endsWith("</span>")) {
            // IE sucks
            String app = _action.substring(23, _action.length() - 7);
            int appnum = -1;
            try {
                appnum = Integer.parseInt(app);
            } catch (NumberFormatException nfe) {}
            if (appnum >= 0)
                startClient(appnum);
            else
                startWebApp(app);
        } else {
            addFormError("Unsupported " + _action + ".");
        }
    }
    
    public void setSettings(Map settings) { _settings = new HashMap(settings); }
    
    private void saveClientChanges() {
        List clients = ClientAppConfig.getClientApps(_context);
        for (int cur = 0; cur < clients.size(); cur++) {
            ClientAppConfig ca = (ClientAppConfig) clients.get(cur);
            Object val = _settings.get(cur + ".enabled");
            if (! "webConsole".equals(ca.clientName))
                ca.disabled = val == null;
        }
        ClientAppConfig.writeClientAppConfig(_context, clients);
        addFormNotice("Client configuration saved successfully - restart required to take effect.");
    }

    private void startClient(int i) {
        List clients = ClientAppConfig.getClientApps(_context);
        if (i >= clients.size()) {
            addFormError("Bad client index.");
            return;
        }
        ClientAppConfig ca = (ClientAppConfig) clients.get(i);
        LoadClientAppsJob.runClient(ca.className, ca.clientName, LoadClientAppsJob.parseArgs(ca.args), _log);
        addFormNotice("Client " + ca.clientName + " started.");
    }

    private void saveWebAppChanges() {
        Properties props = RouterConsoleRunner.webAppProperties();
        Set keys = props.keySet();
        int cur = 0;
        for (Iterator iter = keys.iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            if (! (name.startsWith(RouterConsoleRunner.PREFIX) && name.endsWith(RouterConsoleRunner.ENABLED)))
                continue;
            String app = name.substring(RouterConsoleRunner.PREFIX.length(), name.lastIndexOf(RouterConsoleRunner.ENABLED));
            Object val = _settings.get(app + ".enabled");
            if (! RouterConsoleRunner.ROUTERCONSOLE.equals(app))
                props.setProperty(name, "" + (val != null));
        }
        RouterConsoleRunner.storeWebAppProperties(props);
        addFormNotice("WebApp configuration saved successfully - restart required to take effect.");
    }

    // Big hack for the moment, not using properties for directory and port
    // Go through all the Jetty servers, find the one serving port 7657,
    // requested and add the .war to that one
    private void startWebApp(String app) {
        Collection c = Server.getHttpServers();
        for (int i = 0; i < c.size(); i++) {
            Server s = (Server) c.toArray()[i];
            HttpListener[] hl = s.getListeners();
            for (int j = 0; j < hl.length; j++) {
                if (hl[j].getPort() == 7657) {
                    try {
                        File path = new File(_context.getBaseDir(), "webapps");
                        path = new File(path, app + ".war");
                        s.addWebApplication("/"+ app, path.getAbsolutePath()).start();
                        // no passwords... initialize(wac);
                        addFormNotice("WebApp <a href=\"/" + app + "/\">" + app + "</a> started.");
                    } catch (Exception ioe) {
                        addFormError("Failed to start " + app + " " + ioe + ".");
                    }
                    return;
                }
            }
        }
        addFormError("Failed to find server.");
    }
}
