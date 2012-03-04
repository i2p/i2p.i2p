package net.i2p.router.web;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.i2p.router.client.ClientManagerFacadeImpl;
import net.i2p.router.startup.ClientAppConfig;
import net.i2p.router.startup.LoadClientAppsJob;

import org.mortbay.jetty.handler.ContextHandlerCollection;

/**
 *  Saves changes to clients.config or webapps.config
 */
public class ConfigClientsHandler extends FormHandler {
    private Map _settings;
    
    @Override
    protected void processForm() {
        // set action for when CR is hit in a text input box
        if (_action.length() <= 0) {
            String url = getJettyString("pluginURL");
            if (url != null && url.length() > 0)
                _action = "Install Plugin";
            else
                _action = "Save Client Configuration";
        }

        if (_action.equals(_("Save Client Configuration"))) {
            saveClientChanges();
            return;
        }
        if (_action.equals(_("Save Interface Configuration"))) {
            saveInterfaceChanges();
            return;
        }
        if (_action.equals(_("Save WebApp Configuration"))) {
            saveWebAppChanges();
            return;
        }
        if (_action.equals(_("Save Plugin Configuration"))) {
            savePluginChanges();
            return;
        }
        if (_action.equals(_("Install Plugin"))) {
            installPlugin();
            return;
        }
        if (_action.equals(_("Update All Installed Plugins"))) {
            updateAllPlugins();
            return;
        }
        // value
        if (_action.startsWith("Start ")) {
            String app = _action.substring(6);
            int appnum = -1;
            try {
                appnum = Integer.parseInt(app);
            } catch (NumberFormatException nfe) {}
            if (appnum >= 0) {
                startClient(appnum);
            } else {
                List<String> plugins = PluginStarter.getPlugins();
                if (plugins.contains(app))
                    startPlugin(app);
                else
                    startWebApp(app);
            }
            return;
        }

        // value
        if (_action.startsWith("Delete ")) {
            String app = _action.substring(7);
            int appnum = -1;
            try {
                appnum = Integer.parseInt(app);
            } catch (NumberFormatException nfe) {}
            if (appnum >= 0) {
                deleteClient(appnum);
            } else {
                try {
                    PluginStarter.stopPlugin(_context, app);
                    PluginStarter.deletePlugin(_context, app);
                    addFormNotice(_("Deleted plugin {0}", app));
                } catch (Throwable e) {
                    addFormError(_("Error deleting plugin {0}", app) + ": " + e);
                    _log.error("Error deleting plugin " + app,  e);
                }
            }
            return;
        }

        // value
        if (_action.startsWith("Stop ")) {
            
            String app = _action.substring(5);
            int appnum = -1;
            try {
                appnum = Integer.parseInt(app);
            } catch (NumberFormatException nfe) {}
            if (appnum >= 0) {
                stopClient(appnum);
            } else {
                try {
                    PluginStarter.stopPlugin(_context, app);
                    addFormNotice(_("Stopped plugin {0}", app));
                } catch (Throwable e) {
                    addFormError(_("Error stopping plugin {0}", app) + ": " + e);
                    _log.error("Error stopping plugin " + app,  e);
                }
            }
            return;
        }

        // value
        if (_action.startsWith("Update ")) {
            String app = _action.substring(7);
            updatePlugin(app);
            return;
        }

        // value
        if (_action.startsWith("Check ")) {
            String app = _action.substring(6);
            checkPlugin(app);
            return;
        }

        // label (IE)
        String xStart = _("Start");
        if (_action.toLowerCase(Locale.US).startsWith(xStart + "<span class=hide> ") &&
                   _action.toLowerCase(Locale.US).endsWith("</span>")) {
            // IE sucks
            String app = _action.substring(xStart.length() + 18, _action.length() - 7);
            int appnum = -1;
            try {
                appnum = Integer.parseInt(app);
            } catch (NumberFormatException nfe) {}
            if (appnum >= 0) {
                startClient(appnum);
            } else {
                List<String> plugins = PluginStarter.getPlugins();
                if (plugins.contains(app))
                    startPlugin(app);
                else
                    startWebApp(app);
            }
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
            String desc = getJettyString("desc" + cur);
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
                ca.clientName = getJettyString("name" + cur);
            }
        }

        int newClient = clients.size();
        String newDesc = getJettyString("desc" + newClient);
        if (newDesc != null && newDesc.trim().length() > 0) {
            // new entry
            int spc = newDesc.indexOf(" ");
            String clss = newDesc;
            String args = null;
            if (spc >= 0) {
                clss = newDesc.substring(0, spc);
                args = newDesc.substring(spc + 1);
            }
            String name = getJettyString("name" + newClient);
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
    private String getJettyString(String key) {
        String[] arr = (String[]) _settings.get(key);
        if (arr == null)
            return null;
        return arr[0].trim();
    }

    // STUB for stopClient, not completed yet.
    private void stopClient(int i) {
        List<ClientAppConfig> clients = ClientAppConfig.getClientApps(_context);
        if (i >= clients.size()) {
            addFormError(_("Bad client index."));
            return;
        }
        ClientAppConfig ca = clients.get(i);
        //
        // What do we do here?
        //
        addFormNotice(_("Client") + ' ' + _(ca.clientName) + ' ' + _("stopped") + '.');
    }

    private void startClient(int i) {
        List<ClientAppConfig> clients = ClientAppConfig.getClientApps(_context);
        if (i >= clients.size()) {
            addFormError(_("Bad client index."));
            return;
        }
        ClientAppConfig ca = clients.get(i);
        LoadClientAppsJob.runClient(ca.className, ca.clientName, LoadClientAppsJob.parseArgs(ca.args), _log);
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
        addFormNotice(_("WebApp configuration saved."));
    }

    private void savePluginChanges() {
        Properties props = PluginStarter.pluginProperties();
        Set keys = props.keySet();
        int cur = 0;
        for (Iterator iter = keys.iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            if (! (name.startsWith(PluginStarter.PREFIX) && name.endsWith(PluginStarter.ENABLED)))
                continue;
            String app = name.substring(PluginStarter.PREFIX.length(), name.lastIndexOf(PluginStarter.ENABLED));
            Object val = _settings.get(app + ".enabled");
            props.setProperty(name, "" + (val != null));
        }
        PluginStarter.storePluginProperties(props);
        addFormNotice(_("Plugin configuration saved."));
    }

    /**
     * Big hack for the moment, not using properties for directory and port
     * Go through all the Jetty servers, find the one serving port 7657,
     * requested and add the .war to that one
     */
    private void startWebApp(String app) {
        ContextHandlerCollection s = WebAppStarter.getConsoleServer();
        if (s != null) {
                    try {
                        File path = new File(_context.getBaseDir(), "webapps");
                        path = new File(path, app + ".war");
                        WebAppStarter.startWebApp(_context, s, app, path.getAbsolutePath());
                        addFormNotice(_("WebApp") + " <a href=\"/" + app + "/\">" + _(app) + "</a> " + _("started") + '.');
                    } catch (Throwable e) {
                        addFormError(_("Failed to start") + ' ' + _(app) + " " + e + '.');
                        _log.error("Failed to start webapp " + app, e);
                    }
                    return;
        }
        addFormError(_("Failed to find server."));
    }

    private void installPlugin() {
        String url = getJettyString("pluginURL");
        if (url == null || url.length() <= 0) {
            addFormError(_("No plugin URL specified."));
            return;
        }
        installPlugin(url);
    }

    private void updatePlugin(String app) {
        Properties props = PluginStarter.pluginProperties(_context, app);
        String url = props.getProperty("updateURL");
        if (url == null) {
            addFormError(_("No update URL specified for {0}",app));
            return;
        }
        installPlugin(url);
    }

    /** @since 0.8.13 */
    private void updateAllPlugins() {
        if ("true".equals(System.getProperty(UpdateHandler.PROP_UPDATE_IN_PROGRESS))) {
            addFormError(_("Plugin or update download already in progress."));
            return;
        }
        addFormNotice(_("Updating all plugins"));
        PluginStarter.updateAll(_context);
        // So that update() will post a status to the summary bar before we reload
        try {
           Thread.sleep(1000);
        } catch (InterruptedException ie) {}
    }

    private void installPlugin(String url) {
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

    private void checkPlugin(String app) {
        if ("true".equals(System.getProperty(UpdateHandler.PROP_UPDATE_IN_PROGRESS))) {
            addFormError(_("Plugin or update download already in progress."));
            return;
        }
        PluginUpdateChecker puc = PluginUpdateChecker.getInstance(_context);
        if (puc.isRunning()) {
            addFormError(_("Plugin or update download already in progress."));
            return;
        }
        puc.update(app);
        addFormNotice(_("Checking plugin {0} for updates", app));
        // So that update() will post a status to the summary bar before we reload
        try {
           Thread.sleep(1000);
        } catch (InterruptedException ie) {}
    }

    private void startPlugin(String app) {
        try {
            PluginStarter.startPlugin(_context, app);
            addFormNotice(_("Started plugin {0}", app));
        } catch (Throwable e) {
            addFormError(_("Error starting plugin {0}", app) + ": " + e);
            _log.error("Error starting plugin " + app,  e);
        }
    }

    /**
     *  Handle interface form
     *  @since 0.8.3
     */
    private void saveInterfaceChanges() {
        Map<String, String> changes = new HashMap();
        String port = getJettyString("port");
        if (port != null)
            changes.put(ClientManagerFacadeImpl.PROP_CLIENT_PORT, port);
        String intfc = getJettyString("interface");
        if (intfc != null)
            changes.put(ClientManagerFacadeImpl.PROP_CLIENT_HOST, intfc);
        String user = getJettyString("user");
        if (user != null)
            changes.put(ConfigClientsHelper.PROP_USER, user);
        String pw = getJettyString("pw");
        if (pw != null)
            changes.put(ConfigClientsHelper.PROP_PW, pw);
        String mode = getJettyString("mode");
        boolean disabled = "0".equals(mode);
        boolean ssl = "2".equals(mode);
        changes.put(ConfigClientsHelper.PROP_DISABLE_EXTERNAL,
                                           Boolean.toString(disabled));
        changes.put(ConfigClientsHelper.PROP_ENABLE_SSL,
                                           Boolean.toString(ssl));
        changes.put(ConfigClientsHelper.PROP_AUTH,
                                           Boolean.toString((_settings.get("auth") != null)));
        boolean all = "0.0.0.0".equals(intfc) || "0:0:0:0:0:0:0:0".equals(intfc) ||
                      "::".equals(intfc);
        changes.put(ConfigClientsHelper.BIND_ALL_INTERFACES, Boolean.toString(all));
        if (_context.router().saveConfig(changes, null))
            addFormNotice(_("Interface configuration saved successfully - restart required to take effect."));
        else
            addFormError(_("Error saving the configuration (applied but not saved) - please see the error logs"));
    }
}
