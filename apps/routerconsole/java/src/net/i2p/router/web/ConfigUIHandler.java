package net.i2p.router.web;

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
        String oldTheme = _context.getProperty(CSSHelper.PROP_THEME_NAME, CSSHelper.DEFAULT_THEME);
        if (_config.equals("default")) // obsolete
            _context.router().removeConfigSetting(CSSHelper.PROP_THEME_NAME);
        else
            _context.router().setConfigSetting(CSSHelper.PROP_THEME_NAME, _config);
        if (_context.router().saveConfig()) {
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
