package net.i2p.router.web;

import java.util.Iterator;
import java.util.Properties;

/** set the theme */
public class ConfigUIHandler extends FormHandler {
    private boolean _shouldSave;
    private boolean _universalTheming;
    private String _config;
    
    @Override
    protected void processForm() {
        if (_shouldSave)
            saveChanges();
    }
    
    public void setShouldsave(String moo) { _shouldSave = true; }

    public void setUniversalTheming(String baa) { _universalTheming = true; }

    public void setTheme(String val) {
        _config = val;
    }
    
    /** note - lang change is handled in CSSHelper but we still need to save it here */
    private void saveChanges() {
        if (_config == null)
            return;
        Properties props = _context.readConfigFile(CSSHelper.THEME_CONFIG_FILE);
        String oldTheme = props.getProperty(CSSHelper.PROP_THEME_NAME, CSSHelper.DEFAULT_THEME);
        // Save routerconsole theme first to ensure it is in config file
        if (_config.equals("default")) // obsolete
            props.put(CSSHelper.PROP_THEME_NAME, null);
        else
            props.put(CSSHelper.PROP_THEME_NAME, _config);
        if (_universalTheming) {
            // The routerconsole theme gets set again, but oh well
            for (Iterator it = props.keySet().iterator(); it.hasNext();) {
                String key = (String) it.next();
                props.put(key, _config);
            }
        }
        boolean ok = _context.writeConfigFile(CSSHelper.THEME_CONFIG_FILE, props);
        if (ok) {
            if (!oldTheme.equals(_config))
                addFormNotice(_("Theme change saved.") +
                              " <a href=\"configui\">" +
                              _("Refresh the page to view.") +
                              "</a>");
        } else {
            addFormError(_("Error saving the configuration (applied but not saved) - please see the error logs."));
        }
    }
}
