package net.i2p.router.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.i2p.data.DataHelper;

/**
 * Handler to deal with form submissions from the advanced config form and act
 * upon the values.
 *
 */
public class ConfigAdvancedHandler extends FormHandler {
    //private boolean _forceRestart;
    private boolean _shouldSave;
    private String _config;
    
    @Override
    protected void processForm() {
        if (_shouldSave) {
            saveChanges();
        } else {
            // noop
        }
    }
    
    public void setShouldsave(String moo) { _shouldSave = true; }
    //public void setRestart(String moo) { _forceRestart = true; }
    
    public void setConfig(String val) {
        _config = val;
    }
    
    /**
     * The user made changes to the config and wants to save them, so
     * lets go ahead and do so.
     *
     */
    private void saveChanges() {
        Set<String> unsetKeys = new HashSet(_context.router().getConfigSettings());
        if (_config != null) {
            Properties props = new Properties();
            try {
                DataHelper.loadProps(props, new ByteArrayInputStream(_config.getBytes()));
            } catch (IOException ioe) {
                _log.error("Config error", ioe);
                addFormError(ioe.toString());
                addFormError(_("Error updating the configuration - please see the error logs"));
                return;
            }

            for (Map.Entry e : props.entrySet()) {
                String key = (String) e.getKey();
                String val = (String) e.getValue();
                _context.router().setConfigSetting(key, val);
                unsetKeys.remove(key);
            }

            for (String unsetKey : unsetKeys) {
                _context.router().removeConfigSetting(unsetKey);
            }

            boolean saved = _context.router().saveConfig();
            if (saved) 
                addFormNotice(_("Configuration saved successfully"));
            else
                addFormNotice(_("Error saving the configuration (applied but not saved) - please see the error logs"));
            
            //if (_forceRestart) {
            //    addFormNotice("Performing a soft restart");
            //    _context.router().restart();
            //    addFormNotice("Soft restart complete");
            //}
        }
    }
}
