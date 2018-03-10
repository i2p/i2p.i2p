package net.i2p.router.web.helpers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.i2p.data.DataHelper;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.web.FormHandler;

/**
 * Handler to deal with form submissions from the advanced config form and act
 * upon the values.
 *
 */
public class ConfigAdvancedHandler extends FormHandler {
    //private boolean _forceRestart;
    private boolean _shouldSave;
    private String _oldConfig, _config;
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

    /** @since 0.9.33 */
    public void setNofilter_oldConfig(String val) {
        _oldConfig = val;
    }
    
    /**
     * The user made changes to the config and wants to save them, so
     * lets go ahead and do so.
     *
     * We saved the previous config in the form, so we do a diff between the two.
     * This will reduce the chance of undoing some change that happened in-between.
     */
    private void saveChanges() {
        if (_oldConfig != null && _config != null) {
            Properties oldProps = new Properties();
            Properties props = new Properties();
            try {
                DataHelper.loadProps(oldProps, new ByteArrayInputStream(DataHelper.getUTF8(_oldConfig)));
                DataHelper.loadProps(props, new ByteArrayInputStream(DataHelper.getUTF8(_config)));
            } catch (IOException ioe) {
                _log.error("Config error", ioe);
                addFormError(ioe.toString());
                addFormError(_t("Error updating the configuration - please see the error logs"));
                return;
            }

            Set<String> unsetKeys = new HashSet<String>(oldProps.stringPropertyNames());
            for (Iterator<Map.Entry<Object, Object>> iter = props.entrySet().iterator(); iter.hasNext(); ) {
                Map.Entry<Object, Object> e = iter.next();
                String key = (String) e.getKey();
                String nnew = (String) e.getValue();
                String old = oldProps.getProperty(key);
                unsetKeys.remove(key);
                if (nnew.equals(old)) {
                    // no change
                    iter.remove();
                }
            }
            // what's remaining in unsetKeys will be deleted

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
            // this will rebuild the RI, log in the event log, etc.
            fndf.setFloodfillEnabled(isFF);
        }
        if (saved) 
            addFormNotice(_t("Configuration saved successfully"));
        else
            addFormError(_t("Error saving the configuration (applied but not saved) - please see the error logs"));
    }
}
