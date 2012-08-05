package net.i2p.router.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** set the theme */
public class ConfigUIHandler extends FormHandler {
    private boolean _shouldSave;
    private boolean _universalTheming;
    private String _config;
    
    @Override
    protected void processForm() {
        if (_shouldSave)
            saveChanges();
    }
    
    public void setShouldsave(String moo) { _shouldSave = true; }

    public void setUniversalTheming(String baa) { _universalTheming = true; }

    public void setTheme(String val) {
        _config = val;
    }
    
    /** note - lang change is handled in CSSHelper but we still need to save it here */
    private void saveChanges() {
        if (_config == null)
            return;
        Map<String, String> changes = new HashMap();
        List<String> removes = new ArrayList();
        String oldTheme = _context.getProperty(CSSHelper.PROP_THEME_NAME, CSSHelper.DEFAULT_THEME);
        if (_config.equals("default")) // obsolete
            removes.add(CSSHelper.PROP_THEME_NAME);
        else
            changes.put(CSSHelper.PROP_THEME_NAME, _config);
        if (_universalTheming)
            changes.put(CSSHelper.PROP_UNIVERSAL_THEMING, "true");
        else
            removes.add(CSSHelper.PROP_UNIVERSAL_THEMING);
        boolean ok = _context.router().saveConfig(changes, removes);
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
