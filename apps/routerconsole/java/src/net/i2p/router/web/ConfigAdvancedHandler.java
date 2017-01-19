package net.i2p.router.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import net.i2p.data.DataHelper;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;

/**
 * Handler to deal with form submissions from the advanced config form and act
 * upon the values.
 *
 */
public class ConfigAdvancedHandler extends FormHandler {
    //private boolean _forceRestart;
    private boolean _shouldSave;
    private String _config;
    private String _ff;
    
    @Override
    protected void processForm() {
        if (_shouldSave) {
            if ("ff".equals(_action) && _ff != null) {
                saveFF();
            } else if (isAdvanced()) {
                saveChanges();
            } else {
                addFormError("Save disabled, edit the router.config file to make changes") ;
            }
        } else {
            // noop
        }
    }
    
    public void setShouldsave(String moo) { _shouldSave = true; }
    //public void setRestart(String moo) { _forceRestart = true; }
    
    /** @since 0.9.20 */
    public void setFf(String ff) { _ff = ff; }

    public void setNofilter_config(String val) {
        _config = val;
    }
    
    /**
     * The user made changes to the config and wants to save them, so
     * lets go ahead and do so.
     *
     */
    private void saveChanges() {
        Set<String> unsetKeys = new HashSet<String>(_context.router().getConfigSettings());
        if (_config != null) {
            Properties props = new Properties();
            try {
                DataHelper.loadProps(props, new ByteArrayInputStream(DataHelper.getUTF8(_config)));
            } catch (IOException ioe) {
                _log.error("Config error", ioe);
                addFormError(ioe.toString());
                addFormError(_t("Error updating the configuration - please see the error logs"));
                return;
            }

            for (String key : props.stringPropertyNames()) {
                unsetKeys.remove(key);
            }

            boolean saved = _context.router().saveConfig(props, unsetKeys);
            if (saved) 
                addFormNotice(_t("Configuration saved successfully"));
            else
                addFormError(_t("Error saving the configuration (applied but not saved) - please see the error logs"));
            
            //if (_forceRestart) {
            //    addFormNotice("Performing a soft restart");
            //    _context.router().restart();
            //    addFormNotice("Soft restart complete");
            //}
        }
    }

    /** @since 0.9.20 */
    private void saveFF() {
        boolean saved = _context.router().saveConfig(ConfigAdvancedHelper.PROP_FLOODFILL_PARTICIPANT, _ff);
        if (_ff.equals("false") || _ff.equals("true")) {
            FloodfillNetworkDatabaseFacade fndf = (FloodfillNetworkDatabaseFacade) _context.netDb();
            boolean wasFF = fndf.floodfillEnabled();
            boolean isFF = _ff.equals("true");
            fndf.setFloodfillEnabled(isFF);
            if (wasFF != isFF)
                _context.router().rebuildRouterInfo();
        }
        if (saved) 
            addFormNotice(_t("Configuration saved successfully"));
        else
            addFormError(_t("Error saving the configuration (applied but not saved) - please see the error logs"));
    }
}
