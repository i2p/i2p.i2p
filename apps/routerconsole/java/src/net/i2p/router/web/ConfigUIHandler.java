package net.i2p.router.web;

import java.util.Properties;

/** set the theme */
public class ConfigUIHandler extends FormHandler {
    private boolean _shouldSave;
    private String _config;
    
    @Override
    protected void processForm() {
        if (_shouldSave)
            saveChanges();
    }
    
    public void setShouldsave(String moo) { _shouldSave = true; }
    
    public void setTheme(String val) {
        _config = val;
    }
    
    /** note - lang change is handled in CSSHelper but we still need to save it here */
    private void saveChanges() {
        if (_config == null)
            return;
        Properties props = _context.readConfigFile(CSSHelper.THEME_CONFIG_FILE);
        String oldTheme = props.getProperty(CSSHelper.PROP_THEME_NAME, CSSHelper.DEFAULT_THEME);
        boolean ok;
        if (_config.equals("default")) // obsolete
            props.put(CSSHelper.PROP_THEME_NAME, null);
        else
            props.put(CSSHelper.PROP_THEME_NAME, _config);
        ok = _context.writeConfigFile("themes.config", props);
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
