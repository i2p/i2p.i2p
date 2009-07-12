package net.i2p.router.web;

/** set the theme */
public class ConfigUIHandler extends FormHandler {
    private boolean _shouldSave;
    private String _config;
    
    protected void processForm() {
        if (_shouldSave)
            saveChanges();
    }
    
    public void setShouldsave(String moo) { _shouldSave = true; }
    
    public void setTheme(String val) {
        _config = val;
    }
    
    private void saveChanges() {
        if (_config == null)
            return;
        if (_config.equals("default"))
            _context.router().removeConfigSetting(ConfigUIHelper.PROP_THEME);
        else
            _context.router().setConfigSetting(ConfigUIHelper.PROP_THEME, _config);
        if (_context.router().saveConfig()) 
            addFormNotice("Configuration saved successfully");
        else
            addFormNotice("Error saving the configuration (applied but not saved) - please see the error logs");
    }
}
