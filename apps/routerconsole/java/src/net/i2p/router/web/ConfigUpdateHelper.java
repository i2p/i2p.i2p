package net.i2p.router.web;

import net.i2p.I2PAppContext;
import net.i2p.crypto.TrustedUpdate;
import net.i2p.data.DataHelper;

public class ConfigUpdateHelper extends HelperBase {
    private boolean _dontInstall;

    public ConfigUpdateHelper() {}
    
    /** hook this so we can call dontInstall() once after getting a context */
    @Override
    public void setContextId(String contextId) {
        super.setContextId(contextId);
        _dontInstall = NewsFetcher.getInstance(_context).dontInstall();
    }

    public boolean canInstall() {
        return !_dontInstall;
    }
    
    public boolean updateAvailable() {
        return true;
    }
    
    public String getNewsURL() {
        return getNewsURL(_context);
    }

    /** hack to replace the old news location with the new one, even if they have saved
        the update page at some point */
    public static String getNewsURL(I2PAppContext ctx) {
        String url = ctx.getProperty(ConfigUpdateHandler.PROP_NEWS_URL);
        if (url != null && !url.equals(ConfigUpdateHandler.OLD_DEFAULT_NEWS_URL))
            return url;
        else
            return ConfigUpdateHandler.DEFAULT_NEWS_URL;
    }
    public String getUpdateURL() {
        String url = _context.getProperty(ConfigUpdateHandler.PROP_UPDATE_URL);
        if (url != null)
            return url.replace(",", "\n");
        else
            return ConfigUpdateHandler.DEFAULT_UPDATE_URL;
    }
    public String getProxyHost() {
        return _context.getProperty(ConfigUpdateHandler.PROP_PROXY_HOST, ConfigUpdateHandler.DEFAULT_PROXY_HOST);
    }
    public String getProxyPort() {
        return _context.getProperty(ConfigUpdateHandler.PROP_PROXY_PORT, ConfigUpdateHandler.DEFAULT_PROXY_PORT);
    }
    
    public String getUpdateThroughProxy() {
        String proxy = _context.getProperty(ConfigUpdateHandler.PROP_SHOULD_PROXY, ConfigUpdateHandler.DEFAULT_SHOULD_PROXY);
        if (Boolean.valueOf(proxy).booleanValue()) 
            return "<input type=\"checkbox\" class=\"optbox\" value=\"true\" name=\"updateThroughProxy\" checked=\"true\" >";
        else
            return "<input type=\"checkbox\" class=\"optbox\" value=\"true\" name=\"updateThroughProxy\" >";
    }
    
    public String getUpdateUnsigned() {
        String foo = _context.getProperty(ConfigUpdateHandler.PROP_UPDATE_UNSIGNED);
        if (Boolean.valueOf(foo).booleanValue()) 
            return "<input type=\"checkbox\" class=\"optbox\" value=\"true\" name=\"updateUnsigned\" checked=\"true\" >";
        else
            return "<input type=\"checkbox\" class=\"optbox\" value=\"true\" name=\"updateUnsigned\" >";
    }
    
    private static final long PERIODS[] = new long[] { 12*60*60*1000l, 24*60*60*1000l,
                                                       36*60*60*1000l, 48*60*60*1000l,
                                                       3*24*60*60*1000l, 7*24*60*60*1000l,
                                                       -1l };
    
    public String getRefreshFrequencySelectBox() {
        String freq = _context.getProperty(ConfigUpdateHandler.PROP_REFRESH_FREQUENCY,
                                           ConfigUpdateHandler.DEFAULT_REFRESH_FREQUENCY);
        long ms = ConfigUpdateHandler.DEFAULT_REFRESH_FREQ;
        try { 
            ms = Long.parseLong(freq);
        } catch (NumberFormatException nfe) {}

        StringBuilder buf = new StringBuilder(256);
        buf.append("<select name=\"refreshFrequency\">");
        for (int i = 0; i < PERIODS.length; i++) {
            buf.append("<option value=\"").append(PERIODS[i]);
            if (PERIODS[i] == ms)
                buf.append("\" selected=\"true");
            
            if (PERIODS[i] == -1)
                buf.append("\">" + _("Never") + "</option>\n");
            else
                buf.append("\">" + _("Every") + " ").append(DataHelper.formatDuration2(PERIODS[i])).append("</option>\n");
        }
        buf.append("</select>\n");
        return buf.toString();
    }
    
    /**
     *  Right now the jsp hides the whole select box if _dontInstall is true but this could change
     */
    public String getUpdatePolicySelectBox() {
        String policy = _context.getProperty(ConfigUpdateHandler.PROP_UPDATE_POLICY, ConfigUpdateHandler.DEFAULT_UPDATE_POLICY);
        
        StringBuilder buf = new StringBuilder(256);
        buf.append("<select name=\"updatePolicy\">");
        
        buf.append("<option value=\"notify\"");
        if ("notify".equals(policy) || _dontInstall)
            buf.append(" selected=\"true\"");
        buf.append('>').append(_("Notify only")).append("</option>");

        buf.append("<option value=\"download\"");
        if (_dontInstall)
            buf.append(" disabled=\"true\"");
        else if ("download".equals(policy))
            buf.append(" selected=\"true\"");
        buf.append('>').append(_("Download and verify only")).append("</option>");
        
        if (_context.hasWrapper()) {
            buf.append("<option value=\"install\"");
            if (_dontInstall)
                buf.append(" disabled=\"true\"");
            else if ("install".equals(policy))
                buf.append(" selected=\"true\"");
            buf.append('>').append(_("Download, verify, and restart")).append("</option>");
        }
        
        buf.append("</select>\n");
        return buf.toString();
    }
    
    public String getTrustedKeys() {
        return new TrustedUpdate(_context).getTrustedKeysString();
    }

    public String getZipURL() {
        return _context.getProperty(ConfigUpdateHandler.PROP_ZIP_URL, "");
    }

    public String getNewsStatus() { 
        return NewsFetcher.getInstance(_context).status();
    }
}
