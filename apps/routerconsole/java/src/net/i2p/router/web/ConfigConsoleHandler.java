package net.i2p.router.web;

/**
 * Handler to deal with console config.
 *
 */
public class ConfigConsoleHandler extends FormHandler {

    private boolean _forceRestart;
    private boolean _shouldSave;
    private String _configPass;
    // This is possibly the wrong place for this...
    // private String _configPort;

    @Override
    protected void processForm() {
        if(_shouldSave) {
            saveChanges();
        } else {
            // noop
        }
    }

    public void setShouldsave(String moo) {
        _shouldSave = true;
    }

    public void setRestart(String moo) {
        _forceRestart = true;
    }

    public void setConfigPass(String val) {
        _configPass = val.trim();
    }

    /**
     * The user made changes to the config and wants to save them, so
     * lets go ahead and do so.
     *
     */
    private void saveChanges() {
        if(_configPass != null) {
            _context.router().setConfigSetting("consolePassword", _configPass);
        } else {
            _context.router().setConfigSetting("consolePassword", "");
        }

        boolean saved = _context.router().saveConfig();
        if(saved) {
            addFormNotice("Configuration saved successfully");
        } else {
            addFormNotice("Error saving the configuration (applied but not saved) - please see the error logs");
        }

        if(_forceRestart) {
            addFormNotice("Performing a soft restart");
            _context.router().restart();
            addFormNotice("Soft restart complete");
        }
    }
}
