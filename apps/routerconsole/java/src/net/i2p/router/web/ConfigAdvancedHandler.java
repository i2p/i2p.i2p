package net.i2p.router.web;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Iterator;

/**
 * Handler to deal with form submissions from the advanced config form and act
 * upon the values.
 *
 */
public class ConfigAdvancedHandler extends FormHandler {
    private boolean _forceRestart;
    private boolean _shouldSave;
    private String _config;
    
    protected void processForm() {
        if (_shouldSave) {
            saveChanges();
        } else {
            // noop
        }
    }
    
    public void setShouldsave(String moo) { _shouldSave = true; }
    public void setRestart(String moo) { _forceRestart = true; }
    
    public void setConfig(String val) {
        _config = val;
    }
    
    /**
     * The user made changes to the config and wants to save them, so
     * lets go ahead and do so.
     *
     */
    private void saveChanges() {
        HashSet unsetKeys = new HashSet(_context.router().getConfigMap().keySet());
        if (_config != null) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(_config.getBytes())));
            String line = null;
            try {
                while ( (line = reader.readLine()) != null) {
                    int eq = line.indexOf('=');
                    if (eq == -1) continue;
                    if (eq >= line.length() - 1) continue;
                    String key = line.substring(0, eq).trim();
                    String val = line.substring(eq + 1).trim();
                    _context.router().setConfigSetting(key, val);
                    unsetKeys.remove(key);
                }
            } catch (IOException ioe) {
                addFormError("Error updating the configuration (IOERROR) - please see the error logs");
                return;
            }

            Iterator cleaner = unsetKeys.iterator();
            while (cleaner.hasNext()) {
                String unsetKey = (String)cleaner.next();
                _context.router().removeConfigSetting(unsetKey);
            }

            boolean saved = _context.router().saveConfig();
            if (saved) 
                addFormNotice("Configuration saved successfully");
            else
                addFormNotice("Error saving the configuration (applied but not saved) - please see the error logs");
            
            if (_forceRestart) {
                addFormNotice("Performing a soft restart");
                _context.router().restart();
                addFormNotice("Soft restart complete");
            }
        }
    }
}
