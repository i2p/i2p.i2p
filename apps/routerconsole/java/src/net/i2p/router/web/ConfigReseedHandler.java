package net.i2p.router.web;

import java.util.HashMap;
import java.util.Map;

import net.i2p.router.networkdb.reseed.Reseeder;

/**
 *  @since 0.8.3
 */
public class ConfigReseedHandler extends FormHandler {
    private Map _settings;
    
    @Override
    protected void processForm() {

        if (_action.equals(_("Save changes and reseed now"))) {
            saveChanges();
            boolean reseedInProgress = Boolean.valueOf(System.getProperty("net.i2p.router.web.ReseedHandler.reseedInProgress")).booleanValue();
            if (reseedInProgress) {
                addFormError(_("Reseeding is already in progress"));
            } else {
                // skip the nonce checking in ReseedHandler
                addFormNotice(_("Starting reseed process"));
                (new ReseedHandler(_context)).requestReseed();
            }
            return;
        }
        if (_action.equals(_("Save changes"))) {
            saveChanges();
            return;
        }
        addFormError(_("Unsupported") + ' ' + _action + '.');
    }
    
    public void setSettings(Map settings) { _settings = new HashMap(settings); }

    /** curses Jetty for returning arrays */
    private String getJettyString(String key) {
        String[] arr = (String[]) _settings.get(key);
        if (arr == null)
            return null;
        return arr[0].trim();
    }

    private void saveChanges() {
        String port = getJettyString("port");
        if (port != null)
            _context.router().setConfigSetting(Reseeder.PROP_PROXY_PORT, port);
        String host = getJettyString("host");
        if (host != null)
            _context.router().setConfigSetting(Reseeder.PROP_PROXY_HOST, host);
        String url = getJettyString("reseedURL");
        if (url != null)
            _context.router().setConfigSetting(Reseeder.PROP_RESEED_URL, url.trim().replace("\r\n", ",").replace("\n", ","));
        String mode = getJettyString("mode");
        boolean req = "1".equals(mode);
        boolean disabled = "2".equals(mode);
        _context.router().setConfigSetting(Reseeder.PROP_SSL_REQUIRED,
                                           Boolean.toString(req));
        _context.router().setConfigSetting(Reseeder.PROP_SSL_DISABLE,
                                           Boolean.toString(disabled));
        boolean proxy = getJettyString("enable") != null;
        _context.router().setConfigSetting(Reseeder.PROP_PROXY_ENABLE, Boolean.toString(proxy));
        _context.router().saveConfig();
        addFormNotice(_("Configuration saved successfully."));
    }
}
