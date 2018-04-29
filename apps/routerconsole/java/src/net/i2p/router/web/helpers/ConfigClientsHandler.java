package net.i2p.router.web.helpers;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.i2p.app.ClientApp;
import net.i2p.app.ClientAppState;
import net.i2p.crypto.SU3File;
import net.i2p.crypto.TrustedUpdate;
import net.i2p.data.DataHelper;
import net.i2p.router.client.ClientManagerFacadeImpl;
import net.i2p.router.startup.ClientAppConfig;
import net.i2p.router.startup.LoadClientAppsJob;
import net.i2p.router.update.ConsoleUpdateManager;
import static net.i2p.update.UpdateType.*;
import net.i2p.router.web.ConfigUpdateHandler;
import net.i2p.router.web.ConsolePasswordManager;
import net.i2p.router.web.FormHandler;
import net.i2p.router.web.Messages;
import net.i2p.router.web.NewsHelper;
import net.i2p.router.web.PluginStarter;
import net.i2p.router.web.RouterConsoleRunner;
import net.i2p.router.web.UpdateHandler;
import net.i2p.router.web.WebAppStarter;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.PortMapper;

import org.eclipse.jetty.server.handler.ContextHandlerCollection;

/**
 *  Saves changes to clients.config or webapps.config
 */
public class ConfigClientsHandler extends FormHandler {
    
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

        if (_action.equals(_t("Save Client Configuration"))) {
            saveClientChanges();
            return;
        }
        if (_action.equals(_t("Save Interface Configuration"))) {
            saveInterfaceChanges();
            return;
        }
        if (_action.equals(_t("Save WebApp Configuration"))) {
            saveWebAppChanges();
            return;
        }
        boolean pluginsEnabled = PluginStarter.pluginsEnabled(_context);
        if (_action.equals(_t("Save Plugin Configuration"))) {
            if (pluginsEnabled)
                savePluginChanges();
            else
                addFormError("Plugins disabled");
            return;
        }
        if (_action.equals(_t("Install Plugin"))) {
            if (pluginsEnabled &&
                (_context.getBooleanPropertyDefaultTrue(ConfigClientsHelper.PROP_ENABLE_PLUGIN_INSTALL) ||
                 isAdvanced()))
                installPlugin();
            else
                addFormError("Plugins disabled");
            return;
        }
        if (_action.equals(_t("Install Plugin from File"))) {
            if (pluginsEnabled &&
                (_context.getBooleanPropertyDefaultTrue(ConfigClientsHelper.PROP_ENABLE_PLUGIN_INSTALL) ||
                 isAdvanced()))
                installPluginFromFile();
            else
                addFormError("Plugins disabled");
            return;
        }
        if (_action.equals(_t("Update All Installed Plugins"))) {
            if (pluginsEnabled)
                updateAllPlugins();
            else
                addFormError("Plugins disabled");
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
                if (plugins.contains(app)) {
                    if (pluginsEnabled)
                        startPlugin(app);
                    else
                        addFormError("Plugins disabled");
                } else {
                    startWebApp(app);
                }
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
                if (_context.getBooleanProperty(ConfigClientsHelper.PROP_ENABLE_CLIENT_CHANGE) ||
                    isAdvanced()) {
                    deleteClient(appnum);
                } else {
                    addFormError("Delete client disabled");
                }
            } else if (pluginsEnabled) {
                try {
                    PluginStarter.stopPlugin(_context, app);
                } catch (ClassNotFoundException cnfe) {
                    // don't complain here. Some plugins need to be ran to be deleted.
                    // I tried to check to see if the plugin was ran elsewhere,
                    // and it sait it was when it was not. -- Sponge
                } catch (Throwable e) {
                    addFormError(_t("Error stopping plugin {0}", app) + ": " + e);
                    _log.error("Error stopping plugin " + app,  e);
                }
                try {
                    PluginStarter.deletePlugin(_context, app);
                    addFormNotice(_t("Deleted plugin {0}", app));
                } catch (Throwable e) {
                    addFormError(_t("Error deleting plugin {0}", app) + ": " + e);
                    _log.error("Error deleting plugin " + app,  e);
                }
            } else {
                addFormError("Plugins disabled");
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
                List<String> plugins = PluginStarter.getPlugins();
                if (plugins.contains(app)) {
                    try {
                        if (pluginsEnabled) {
                            PluginStarter.stopPlugin(_context, app);
                            addFormNotice(_t("Stopped plugin {0}", app));
                        } else {
                            addFormError("Plugins disabled");
                        }
                    } catch (Throwable e) {
                        addFormError(_t("Error stopping plugin {0}", app) + ": " + e);
                        _log.error("Error stopping plugin " + app,  e);
                    }
                } else {
                    WebAppStarter.stopWebApp(_context, app);
                    addFormNotice(_t("Stopped webapp {0}", app));
                }
            }
            return;
        }

        // value
        if (_action.startsWith("Update ")) {
            if (pluginsEnabled) {
                String app = _action.substring(7);
                updatePlugin(app);
            } else {
                addFormError("Plugins disabled");
            }
            return;
        }

        // value
        if (_action.startsWith("Check ")) {
            if (pluginsEnabled) {
                String app = _action.substring(6);
                checkPlugin(app);
            } else {
                addFormError("Plugins disabled");
            }
            return;
        }

        // label (IE)
        String xStart = _t("Start");
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
                if (plugins.contains(app)) {
                    if (pluginsEnabled)
                        startPlugin(app);
                    else
                        addFormError("Plugins disabled");
                } else {
                    startWebApp(app);
                }
            }
        } else {
            //addFormError(_t("Unsupported") + ' ' + _action + '.');
        }

    }
    
    private void saveClientChanges() {
        List<ClientAppConfig> clients = ClientAppConfig.getClientApps(_context);
        for (int cur = 0; cur < clients.size(); cur++) {
            ClientAppConfig ca = clients.get(cur);
            Object val = _settings.get(cur + ".enabled");
            if (! (RouterConsoleRunner.class.getName().equals(ca.className)))
                ca.disabled = val == null;
            // edit of an existing entry
            if (_context.getBooleanProperty(ConfigClientsHelper.PROP_ENABLE_CLIENT_CHANGE) ||
                isAdvanced()) {
                String desc = getJettyString("nofilter_desc" + cur);
                if (desc != null) {
                    int spc = desc.indexOf(' ');
                    String clss = desc;
                    String args = null;
                    if (spc >= 0) {
                        clss = desc.substring(0, spc);
                        args = desc.substring(spc + 1);
                    }
                    ca.className = clss;
                    ca.args = args;
                    ca.clientName = getJettyString("nofilter_name" + cur);
                }
            }
        }

        // new client
        if (_context.getBooleanProperty(ConfigClientsHelper.PROP_ENABLE_CLIENT_CHANGE) ||
            isAdvanced()) {
            int newClient = clients.size();
            String newDesc = getJettyString("nofilter_desc" + newClient);
            if (newDesc != null && newDesc.trim().length() > 0) {
                // new entry
                int spc = newDesc.indexOf(' ');
                String clss = newDesc;
                String args = null;
                if (spc >= 0) {
                    clss = newDesc.substring(0, spc);
                    args = newDesc.substring(spc + 1);
                }
                String name = getJettyString("nofilter_name" + newClient);
                if (name == null || name.trim().length() <= 0) name = "new client";
                ClientAppConfig ca = new ClientAppConfig(clss, name, args, 2*60*1000,
                                                         _settings.get(newClient + ".enabled") == null);  // true for disabled
                clients.add(ca);
                addFormNotice(_t("New client added") + ": " + name + " (" + clss + ").");
            }
        }

        ClientAppConfig.writeClientAppConfig(_context, clients);
        addFormNotice(_t("Client configuration saved successfully"));
        //addFormNotice(_t("Restart required to take effect"));
    }

    /**
     *  @since Implemented in 0.9.6 using ClientAppManager
     */
    private void stopClient(int i) {
        List<ClientAppConfig> clients = ClientAppConfig.getClientApps(_context);
        if (i >= clients.size()) {
            addFormError(_t("Bad client index."));
            return;
        }
        ClientAppConfig ca = clients.get(i);
        ClientApp clientApp = _context.routerAppManager().getClientApp(ca.className, LoadClientAppsJob.parseArgs(ca.args));
        if (clientApp != null && clientApp.getState() == ClientAppState.RUNNING) {
            try {
                // todo parseArgs(ca.stopArgs) ?
                clientApp.shutdown(null);
                addFormNotice(_t("Client {0} stopped", ca.clientName));
                // Give a chance for status to update
                try {
                   Thread.sleep(1000);
                } catch (InterruptedException ie) {}
            } catch (Throwable t) {
                addFormError("Cannot stop client " + ca.className + ": " + t);
                _log.error("Error stopping client " + ca.className,  t);
            }
        } else {
            addFormError("Cannot stop client " + i + ": " + ca.className);
        }
    }

    private void startClient(int i) {
        List<ClientAppConfig> clients = ClientAppConfig.getClientApps(_context);
        if (i >= clients.size()) {
            addFormError(_t("Bad client index."));
            return;
        }
        ClientAppConfig ca = clients.get(i);
        LoadClientAppsJob.runClient(ca.className, ca.clientName, LoadClientAppsJob.parseArgs(ca.args), _context, _log);
        addFormNotice(_t("Client {0} started", ca.clientName));
        // Give a chance for status to update
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ie) {}
    }

    private void deleteClient(int i) {
        List<ClientAppConfig> clients = ClientAppConfig.getClientApps(_context);
        if (i < 0 || i >= clients.size()) {
            addFormError(_t("Bad client index."));
            return;
        }
        ClientAppConfig ca = clients.remove(i);
        ClientAppConfig.writeClientAppConfig(_context, clients);
        addFormNotice(_t("Client {0} deleted", ca.clientName));
    }

    private void saveWebAppChanges() {
        Properties props = RouterConsoleRunner.webAppProperties(_context);
        Set<String> keys = props.stringPropertyNames();
        for (String name : keys) {
            if (! (name.startsWith(RouterConsoleRunner.PREFIX) && name.endsWith(RouterConsoleRunner.ENABLED)))
                continue;
            String app = name.substring(RouterConsoleRunner.PREFIX.length(), name.lastIndexOf(RouterConsoleRunner.ENABLED));
            Object val = _settings.get(app + ".enabled");
            if (! RouterConsoleRunner.ROUTERCONSOLE.equals(app))
                props.setProperty(name, "" + (val != null));
        }
        RouterConsoleRunner.storeWebAppProperties(_context, props);
        addFormNotice(_t("WebApp configuration saved."));
    }

    private void savePluginChanges() {
        Properties props = PluginStarter.pluginProperties();
        Set<String> keys = props.stringPropertyNames();
        for (String name : keys) {
            if (! (name.startsWith(PluginStarter.PREFIX) && name.endsWith(PluginStarter.ENABLED)))
                continue;
            String app = name.substring(PluginStarter.PREFIX.length(), name.lastIndexOf(PluginStarter.ENABLED));
            Object val = _settings.get(app + ".enabled");
            props.setProperty(name, "" + (val != null));
        }
        PluginStarter.storePluginProperties(props);
        addFormNotice(_t("Plugin configuration saved."));
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
                        addFormNoticeNoEscape(_t("WebApp") + " <a href=\"/" + app + "/\">" + _t(app) + "</a> " + _t("started") + '.');
                    } catch (Throwable e) {
                        addFormError(_t("Failed to start") + ' ' + _t(app) + ": " + e);
                        _log.error("Failed to start webapp " + app, e);
                    }
                    return;
        }
        addFormError(_t("Failed to find server."));
    }

    private void installPlugin() {
        String url = getJettyString("pluginURL");
        if (url == null || url.length() <= 0) {
            addFormError(_t("No plugin URL specified."));
            return;
        }
        installPlugin(null, url);
    }

    /**
     *  @since 0.9.19
     */
    private void installPluginFromFile() {
        InputStream in = _requestWrapper.getInputStream("pluginFile");
        // go to some trouble to verify it's an su3 or xpi2p file before
        // passing it along, so we can display a good error message
        byte[] su3Magic = DataHelper.getASCII(SU3File.MAGIC);
        byte[] zipMagic = new byte[] { 0x50, 0x4b, 0x03, 0x04 };
        byte[] magic = new byte[TrustedUpdate.HEADER_BYTES + zipMagic.length];
        File tmp =  null;
        OutputStream out = null;
        try {
            // non-null but zero bytes if no file entered, don't know why
            if (in == null || in.available() <= 0) {
                addFormError(_t("You must enter a file"));
                return;
            }
            DataHelper.read(in, magic);
            boolean isSU3 = DataHelper.eq(magic, 0, su3Magic, 0, su3Magic.length);
            if (!isSU3) {
                if (!DataHelper.eq(magic, TrustedUpdate.HEADER_BYTES, zipMagic, 0, zipMagic.length)) {
                    String name = _requestWrapper.getFilename("pluginFile");
                    if (name == null)
                        name = "File";
                    throw new IOException(name + " is not an xpi2p or su3 plugin");
                }
            }
            tmp =  new File(_context.getTempDir(), "plugin-" + _context.random().nextInt() + (isSU3 ? ".su3" : ".xpi2p"));
            out = new BufferedOutputStream(new SecureFileOutputStream(tmp));
            out.write(magic);
            DataHelper.copy(in, out);
            out.close();
            String url = tmp.toURI().toString();
            // threaded... TODO inline to get better result to UI?
            installPlugin(null, url);
            // above sleeps 1000, give it some more time?
            // or check for complete?
            ConsoleUpdateManager mgr = UpdateHandler.updateManager(_context);
            if (mgr == null)
                return;
            for (int i = 0; i < 20; i++) {
                if (!mgr.isUpdateInProgress(PLUGIN)) {
                    tmp.delete();
                    break;
                }
                try {
                   Thread.sleep(500);
                } catch (InterruptedException ie) {}
             }
             String status = mgr.getStatus();
             if (status != null && status.length() > 0)
                 addFormNoticeNoEscape(status);
        } catch (IOException ioe) {
            addFormError(_t("Install from file failed") + " - " + ioe.getMessage());
        } finally {
            // it's really a ByteArrayInputStream but we'll play along...
            if (in != null)
                try { in.close(); } catch (IOException ioe) {}
            if (out != null)  try { out.close(); } catch (IOException ioe) {}
        }
    }

    private void updatePlugin(String app) {
        Properties props = PluginStarter.pluginProperties(_context, app);
        String url = props.getProperty("updateURL.su3");
        if (url == null)
            url = props.getProperty("updateURL");
        if (url == null) {
            addFormError(_t("No update URL specified for {0}",app));
            return;
        }
        installPlugin(app, url);
    }

    /** @since 0.8.13 */
    private void updateAllPlugins() {
        if (NewsHelper.isAnyUpdateInProgress()) {
            addFormError(_t("Plugin or update download already in progress."));
            return;
        }
        if (!verifyProxy())
            return;
        addFormNotice(_t("Updating all plugins"));
        PluginStarter.updateAll(_context);
        // So that update() will post a status to the summary bar before we reload
        try {
           Thread.sleep(1000);
        } catch (InterruptedException ie) {}
    }

    /**
     *  @param app null for a new install
     *  @param url http: or file:
     */
    private void installPlugin(String app, String url) {
        ConsoleUpdateManager mgr = UpdateHandler.updateManager(_context);
        if (mgr == null) {
            addFormError("Update manager not registered, cannot install");
            return;
        }
        if (mgr.isUpdateInProgress()) {
            addFormError(_t("Plugin or update download already in progress."));
            return;
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException use) {
            addFormError(_t("Bad URL {0}", url));
            return;
        }
        if (!url.startsWith("file:")) {
            if (uri.getScheme() == null || uri.getHost() == null || uri.getPath() == null || uri.getPath().length() <= 1) {
                addFormError(_t("Bad URL {0}", url));
                return;
            }
            if (!verifyProxy())
                return;
        }
        if (mgr.installPlugin(app, uri)) {
            if (url.startsWith("file:"))
                addFormNotice(_t("Installing plugin from {0}", uri.getPath()));
            else
                addFormNotice(_t("Downloading plugin from {0}", url));
        } else {
            addFormError("Cannot install, check logs");
        }
        // So that update() will post a status to the summary bar before we reload
        try {
           Thread.sleep(5000);
        } catch (InterruptedException ie) {}
    }

    private void checkPlugin(String app) {
        ConsoleUpdateManager mgr = UpdateHandler.updateManager(_context);
        if (mgr == null) {
            addFormError("Update manager not registered, cannot check");
            return;
        }
        if (!verifyProxy())
            return;
        mgr.check(PLUGIN, app);
        addFormNotice(_t("Checking plugin {0} for updates", app));
        // So that update() will post a status to the summary bar before we reload
        try {
           Thread.sleep(1000);
        } catch (InterruptedException ie) {}
    }

    /**
     *  Plugin checks, updates, and installs are always proxied.
     *  See if the proxy tunnel is available, unless we're configured
     *  to use something else (probably not).
     *  Outputs form error if returning false.
     *
     *  @return true if available
     *  @since 0.9.20
     */
    private boolean verifyProxy() {
        String proxyHost = _context.getProperty(ConfigUpdateHandler.PROP_PROXY_HOST, ConfigUpdateHandler.DEFAULT_PROXY_HOST);
        int proxyPort = ConfigUpdateHandler.proxyPort(_context);
        boolean rv = !
            (proxyPort == ConfigUpdateHandler.DEFAULT_PROXY_PORT_INT &&
             proxyHost.equals(ConfigUpdateHandler.DEFAULT_PROXY_HOST) &&
             !_context.portMapper().isRegistered(PortMapper.SVC_HTTP_PROXY));
        if (!rv)
            addFormError(_t("HTTP client proxy tunnel must be running"));
        return rv;
    }

    private void startPlugin(String app) {
        try {
            PluginStarter.startPlugin(_context, app);
            // linkify the app name for the message if available
            Properties props = PluginStarter.pluginProperties(_context, app);
            String name = ConfigClientsHelper.stripHTML(props, "consoleLinkName_" + Messages.getLanguage(_context));
            if (name == null)
                name = ConfigClientsHelper.stripHTML(props, "consoleLinkName");
            String url = ConfigClientsHelper.stripHTML(props, "consoleLinkURL");
            if (name != null && url != null && name.length() > 0 && url.length() > 0) {
                app = "<a href=\"" + url + "\">" + name + "</a>";
                addFormNoticeNoEscape(_t("Started plugin {0}", app));
            } else {
                addFormNotice(_t("Started plugin {0}", app));
            }
        } catch (Throwable e) {
            addFormError(_t("Error starting plugin {0}", app) + ": " + e);
            _log.error("Error starting plugin " + app,  e);
        }
    }

    /**
     *  Handle interface form
     *  @since 0.8.3
     */
    private void saveInterfaceChanges() {
        Map<String, String> changes = new HashMap<String, String>();
        String port = getJettyString("port");
        if (port != null)
            changes.put(ClientManagerFacadeImpl.PROP_CLIENT_PORT, port);
        String intfc = getJettyString("interface");
        if (intfc != null)
            changes.put(ClientManagerFacadeImpl.PROP_CLIENT_HOST, intfc);
        String user = getJettyString("user");
        String pw = getJettyString("nofilter_pw");
        if (user != null && pw != null && user.length() > 0 && pw.length() > 0) {
            ConsolePasswordManager mgr = new ConsolePasswordManager(_context);
            mgr.saveHash(ConfigClientsHelper.PROP_AUTH, user, pw);
            addFormNotice(_t("Added user {0}", user));
        }
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
        if (_context.router().saveConfig(changes, null)) {
            addFormNotice(_t("Interface configuration saved"));
            addFormNotice(_t("Restart required to take effect"));
        } else
            addFormError(_t("Error saving the configuration (applied but not saved) - please see the error logs"));
    }
}
