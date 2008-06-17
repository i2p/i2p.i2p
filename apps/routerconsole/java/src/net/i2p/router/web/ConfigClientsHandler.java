package net.i2p.router.web;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.i2p.data.DataFormatException;
import net.i2p.router.startup.ClientAppConfig;
import net.i2p.util.Log;

/**
 *  Saves changes to clients.config or webapps.config
 */
public class ConfigClientsHandler extends FormHandler {
    private Log _log;
    private Map _settings;
    
    public ConfigClientsHandler() {}
    
    protected void processForm() {
        if (_action.startsWith("Save Client")) {
            saveClientChanges();
        } else if (_action.startsWith("Save WebApp")) {
            saveWebAppChanges();
        } else {
            addFormError("Unsupported " + _action);
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
        addFormNotice("Client configuration saved successfully - restart required to take effect");
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
        addFormNotice("WebApp configuration saved successfully - restart required to take effect");
    }
}
