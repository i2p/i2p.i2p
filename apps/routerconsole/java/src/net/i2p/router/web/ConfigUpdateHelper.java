package net.i2p.router.web;

import java.util.List;
import net.i2p.crypto.TrustedUpdate;
import net.i2p.router.RouterContext;

public class ConfigUpdateHelper {
    private RouterContext _context;
    /**
     * Configure this bean to query a particular router context
     *
     * @param contextId begging few characters of the routerHash, or null to pick
     *                  the first one we come across.
     */
    public void setContextId(String contextId) {
        try {
            _context = ContextHelper.getContext(contextId);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public ConfigUpdateHelper() {}
    
    public boolean updateAvailable() {
        return true;
    }
    
    public String getNewsURL() {
        String url = _context.getProperty(ConfigUpdateHandler.PROP_NEWS_URL);
        if (url != null)
            return url;
        else
            return ConfigUpdateHandler.DEFAULT_NEWS_URL;
    }
    public String getUpdateURL() {
        String url = _context.getProperty(ConfigUpdateHandler.PROP_UPDATE_URL);
        if (url != null)
            return url;
        else
            return ConfigUpdateHandler.DEFAULT_UPDATE_URL;
    }
    
    public String getUpdateThroughProxy() {
        String proxy = _context.getProperty(ConfigUpdateHandler.PROP_SHOULD_PROXY, ConfigUpdateHandler.DEFAULT_SHOULD_PROXY);
        if (Boolean.valueOf(proxy).booleanValue()) 
            return "<input type=\"checkbox\" value=\"true\" name=\"updateThroughProxy\" checked=\"true\" >";
        else
            
            return "<input type=\"checkbox\" value=\"true\" name=\"updateThroughProxy\" >";
    }
    
    public String getRefreshFrequencySelectBox() {
        return "<select name=\"refreshFrequency\">" +
                "<option value=\"" + (12*60*60*1000) + "\">Twice daily</option>" +
                "<option value=\"" + (24*60*60*1000) + "\" selected=\"true\" >Daily</option>" +
                "<option value=\"" + (48*60*60*1000) + "\">Every two days</option>" +
                "<option value=\"" + -1 + "\">Never</option>" +
                "</select>";
    }
    public String getUpdatePolicySelectBox() {
        return "<select name=\"updatePolicy\">" +
                "<option value=\"notify\">Notify only</option>" +
                "<option value=\"download\">Download but don't install</option>" +
                "<option value=\"install\">Install</option>" +
                "</select>";
    }
    public String getTrustedKeys() {
        StringBuffer buf = new StringBuffer(1024);
        TrustedUpdate up = new TrustedUpdate(_context);
        List keys = up.getTrustedKeys();
        for (int i = 0; i < keys.size(); i++) 
            buf.append((String)keys.get(i)).append('\n');
        return buf.toString();
    }
}
