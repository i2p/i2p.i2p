package net.i2p.router.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
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

            for (Object key : props.keySet()) {
                unsetKeys.remove(key);
            }

            boolean saved = _context.router().saveConfig(props, unsetKeys);
            if (saved) 
                addFormNotice(_("Configuration saved successfully"));
            else
                addFormError(_("Error saving the configuration (applied but not saved) - please see the error logs"));
            
            //if (_forceRestart) {
            //    addFormNotice("Performing a soft restart");
            //    _context.router().restart();
            //    addFormNotice("Soft restart complete");
            //}
        }
    }
}
