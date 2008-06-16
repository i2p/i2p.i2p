package net.i2p.router.web;

import java.util.HashMap;
import java.util.Map;
import net.i2p.data.DataFormatException;
import net.i2p.util.Log;

/**
 *
 */
public class ConfigClientsHandler extends FormHandler {
    private Log _log;
    private Map _settings;
    private boolean _shouldSave;
    
    public ConfigClientsHandler() {
        _shouldSave = false;
    }
    
    protected void processForm() {
        if (_shouldSave) {
            saveChanges();
        } else {
            // noop
            addFormError("Unimplemented");
        }
    }
    
    public void setShouldsave(String moo) { 
        if ( (moo != null) && (moo.equals("Save changes")) )
            _shouldSave = true; 
    }
    
    public void setSettings(Map settings) { _settings = new HashMap(settings); }
    
    /**
     * The user made changes to the network config and wants to save them, so
     * lets go ahead and do so.
     *
     */
    private void saveChanges() {
        _log = _context.logManager().getLog(ConfigClientsHandler.class);
        boolean saveRequired = false;
        
        int updated = 0;
        int index = 0;
        
        if (updated > 0)
            addFormNotice("Updated settings");
        
        if (saveRequired) {
            boolean saved = _context.router().saveConfig();
            if (saved) 
                addFormNotice("Exploratory tunnel configuration saved successfully");
            else
                addFormNotice("Error saving the configuration (applied but not saved) - please see the error logs");
        }
    }
}
