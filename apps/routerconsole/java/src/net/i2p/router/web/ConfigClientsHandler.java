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
    private Log configClient_log;
    private Map _settings;
    
    public ConfigClientsHandler() {
        configClient_log = ContextHelper.getContext(null).logManager().getLog(ConfigClientsHandler.class);
    }

    @Override
    protected void processForm() {
        if (_action.equals(_("Save Client Configuration"))) {
            saveClientChanges();
            return;
        }
        if (_action.equals(_("Save WebApp Configuration"))) {
            saveWebAppChanges();
            return;
        }
        if (_action.equals(_("Install Plugin"))) {
            installPlugin();
            return;
        }
        // value
        if (_action.startsWith("Start ")) {
            String app = _action.substring(6);
            int appnum = -1;
            try {
                appnum = Integer.parseInt(app);
            } catch (NumberFormatException nfe) {}
            if (appnum >= 0)
                startClient(appnum);
            else
                startWebApp(app);
            return;
        }

        // value
        if (_action.startsWith("Delete ")) {
            String app = _action.substring(7);
            int appnum = -1;
            try {
                appnum = Integer.parseInt(app);
            } catch (NumberFormatException nfe) {}
            if (appnum >= 0)
                deleteClient(appnum);
            return;
        }
        // label (IE)
        String xStart = _("Start");
        if (_action.toLowerCase().startsWith(xStart + "<span class=hide> ") &&
                   _action.toLowerCase().endsWith("</span>")) {
            // IE sucks
            String app = _action.substring(xStart.length() + 18, _action.length() - 7);
            int appnum = -1;
            try {
                appnum = Integer.parseInt(app);
            } catch (NumberFormatException nfe) {}
            if (appnum >= 0)
                startClient(appnum);
            else
                startWebApp(app);
        } else {
            addFormError(_("Unsupported") + ' ' + _action + '.');
        }
    }
    
    public void setSettings(Map settings) { _settings = new HashMap(settings); }
    
    private void saveClientChanges() {
        List<ClientAppConfig> clients = ClientAppConfig.getClientApps(_context);
        for (int cur = 0; cur < clients.size(); cur++) {
            ClientAppConfig ca = clients.get(cur);
            Object val = _settings.get(cur + ".enabled");
            if (! ("webConsole".equals(ca.clientName) || "Web console".equals(ca.clientName)))
                ca.disabled = val == null;
            // edit of an existing entry
            String desc = getString("desc" + cur);
            if (desc != null) {
                int spc = desc.indexOf(" ");
                String clss = desc;
                String args = null;
                if (spc >= 0) {
                    clss = desc.substring(0, spc);
                    args = desc.substring(spc + 1);
                }
                ca.className = clss;
                ca.args = args;
                ca.clientName = getString("name" + cur);
            }
        }

        int newClient = clients.size();
        String newDesc = getString("desc" + newClient);
        if (newDesc != null && newDesc.trim().length() > 0) {
            // new entry
            int spc = newDesc.indexOf(" ");
            String clss = newDesc;
            String args = null;
            if (spc >= 0) {
                clss = newDesc.substring(0, spc);
                args = newDesc.substring(spc + 1);
            }
            String name = getString("name" + newClient);
            if (name == null || name.trim().length() <= 0) name = "new client";
            ClientAppConfig ca = new ClientAppConfig(clss, name, args, 2*60*1000,
                                                     _settings.get(newClient + ".enabled") != null);
            clients.add(ca);
            addFormNotice(_("New client added") + ": " + name + " (" + clss + ").");
        }

        ClientAppConfig.writeClientAppConfig(_context, clients);
        addFormNotice(_("Client configuration saved successfully - restart required to take effect."));
    }

    /** curses Jetty for returning arrays */
    private String getString(String key) {
        String[] arr = (String[]) _settings.get(key);
        if (arr == null)
            return null;
        return arr[0];
    }

    private void startClient(int i) {
        List<ClientAppConfig> clients = ClientAppConfig.getClientApps(_context);
        if (i >= clients.size()) {
            addFormError(_("Bad client index."));
            return;
        }
        ClientAppConfig ca = clients.get(i);
        LoadClientAppsJob.runClient(ca.className, ca.clientName, LoadClientAppsJob.parseArgs(ca.args), configClient_log);
        addFormNotice(_("Client") + ' ' + _(ca.clientName) + ' ' + _("started") + '.');
    }

    private void deleteClient(int i) {
        List<ClientAppConfig> clients = ClientAppConfig.getClientApps(_context);
        if (i < 0 || i >= clients.size()) {
            addFormError(_("Bad client index."));
            return;
        }
        ClientAppConfig ca = clients.remove(i);
        ClientAppConfig.writeClientAppConfig(_context, clients);
        addFormNotice(_("Client") + ' ' + _(ca.clientName) + ' ' + _("deleted") + '.');
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
        addFormNotice(_("WebApp configuration saved successfully - restart required to take effect."));
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
                        WebAppStarter.startWebApp(_context, s, app, path.getAbsolutePath());
                        addFormNotice(_("WebApp") + " <a href=\"/" + app + "/\">" + _(app) + "</a> " + _("started") + '.');
                    } catch (Exception ioe) {
                        addFormError(_("Failed to start") + ' ' + _(app) + " " + ioe + '.');
                    }
                    return;
                }
            }
        }
        addFormError(_("Failed to find server."));
    }

    private void installPlugin() {
        String url = getString("pluginURL");
        if (url == null || url.length() <= 0) {
            addFormError(_("No plugin URL specified."));
            return;
        }
        if ("true".equals(System.getProperty(UpdateHandler.PROP_UPDATE_IN_PROGRESS))) {
            addFormError(_("Plugin or update download already in progress."));
            return;
        }
        PluginUpdateHandler puh = PluginUpdateHandler.getInstance(_context);
        if (puh.isRunning()) {
            addFormError(_("Plugin or update download already in progress."));
            return;
        }
        puh.update(url);
        addFormNotice(_("Downloading plugin from {0}", url));
        // So that update() will post a status to the summary bar before we reload
        try {
           Thread.sleep(1000);
        } catch (InterruptedException ie) {}
    }
}
