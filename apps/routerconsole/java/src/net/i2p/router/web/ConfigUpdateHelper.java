package net.i2p.router.web;

import net.i2p.I2PAppContext;
import net.i2p.crypto.TrustedUpdate;
import net.i2p.data.DataHelper;
import net.i2p.util.PortMapper;

public class ConfigUpdateHelper extends HelperBase {
    private boolean _dontInstall;

    public ConfigUpdateHelper() {}
    
    /** hook this so we can call dontInstall() once after getting a context */
    @Override
    public void setContextId(String contextId) {
        super.setContextId(contextId);
        _dontInstall = NewsHelper.dontInstall(_context);
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
        if (url != null && !url.equals(ConfigUpdateHandler.OLD_DEFAULT_NEWS_URL) &&
            !url.equals(ConfigUpdateHandler.DEFAULT_NEWS_URL) &&
            !url.equals(ConfigUpdateHandler.OLD_DEFAULT_NEWS_URL_SU3))
            return url;
        else
            return ConfigUpdateHandler.DEFAULT_NEWS_URL_SU3;
    }

    public String getUpdateURL() {
        String url = _context.getProperty(ConfigUpdateHandler.PROP_UPDATE_URL);
        if (url != null)
            return url.replace(",", "\n");
        else
            return ConfigUpdateHandler.DEFAULT_UPDATE_URL;
    }

    public String getProxyHost() {
        if (isInternal())
            return _t("internal") + "\" readonly=\"readonly";
        return _context.getProperty(ConfigUpdateHandler.PROP_PROXY_HOST, ConfigUpdateHandler.DEFAULT_PROXY_HOST);
    }

    public String getProxyPort() {
        if (isInternal())
            return _t("internal") + "\" readonly=\"readonly";
        return Integer.toString(ConfigUpdateHandler.proxyPort(_context));
    }

    /**
     *  This should almost always be true.
     *  @return true if settings are at defaults and proxy is registered
     *  @since 0.8.13
     */
    private boolean isInternal() {
        String host = _context.getProperty(ConfigUpdateHandler.PROP_PROXY_HOST);
        String port = _context.getProperty(ConfigUpdateHandler.PROP_PROXY_PORT);
        return (host == null || host.equals(ConfigUpdateHandler.DEFAULT_PROXY_HOST)) &&
               (port == null || port.equals(ConfigUpdateHandler.DEFAULT_PROXY_PORT)) &&
               _context.portMapper().getPort(PortMapper.SVC_HTTP_PROXY) == ConfigUpdateHandler.DEFAULT_PROXY_PORT_INT;
    }
    
    public String getUpdateThroughProxy() {
        if (_context.getProperty(ConfigUpdateHandler.PROP_SHOULD_PROXY, ConfigUpdateHandler.DEFAULT_SHOULD_PROXY))
            return "<input type=\"checkbox\" class=\"optbox\" value=\"true\" name=\"updateThroughProxy\" id=\"updateThroughProxy\" checked=\"checked\" >";
        else
            return "<input type=\"checkbox\" class=\"optbox\" value=\"true\" name=\"updateThroughProxy\" id=\"updateThroughProxy\" >";
    }
    
    /** @since 0.9.9 */
    public String getNewsThroughProxy() {
        if (_context.getProperty(ConfigUpdateHandler.PROP_SHOULD_PROXY_NEWS, ConfigUpdateHandler.DEFAULT_SHOULD_PROXY_NEWS))
            return "<input type=\"checkbox\" class=\"optbox\" value=\"true\" name=\"newsThroughProxy\" id=\"newsThroughProxy\" checked=\"checked\" >";
        else
            return "<input type=\"checkbox\" class=\"optbox\" value=\"true\" name=\"newsThroughProxy\" id=\"newsThroughProxy\" >";
    }
    
    public String getUpdateUnsigned() {
        return "<input type=\"checkbox\" class=\"optbox\" value=\"true\" name=\"updateUnsigned\" id=\"updateUnsigned\" " +
               getChecked(ConfigUpdateHandler.PROP_UPDATE_UNSIGNED) + '>';
    }
    
    /** @since 0.9.20 */
    public String getUpdateDevSU3() {
        return "<input type=\"checkbox\" class=\"optbox\" value=\"true\" name=\"updateDevSU3\" id=\"updateDevSU3\" " +
               getChecked(ConfigUpdateHandler.PROP_UPDATE_DEV_SU3) + '>';
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
            if (ms <= 0)
                ms = -1;
        } catch (NumberFormatException nfe) {}

        StringBuilder buf = new StringBuilder(256);
        buf.append("<select name=\"refreshFrequency\">");
        for (int i = 0; i < PERIODS.length; i++) {
            buf.append("<option value=\"").append(PERIODS[i]).append("\" ");
            if (PERIODS[i] == ms)
                buf.append(SELECTED);
            
            if (PERIODS[i] == -1)
                buf.append(">").append(_t("Never")).append("</option>\n");
            else
                buf.append(">").append(_t("Every")).append(' ').append(DataHelper.formatDuration2(PERIODS[i])).append("</option>\n");
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
            buf.append(SELECTED);
        buf.append('>').append(_t("Notify only")).append("</option>");

        buf.append("<option value=\"download\"");
        if (_dontInstall)
            buf.append(" disabled=\"disabled\"");
        else if ("download".equals(policy))
            buf.append(SELECTED);
        buf.append('>').append(_t("Download and verify only")).append("</option>");
        
        if (_context.hasWrapper()) {
            buf.append("<option value=\"install\"");
            if (_dontInstall)
                buf.append(" disabled=\"disabled\"");
            else if ("install".equals(policy))
                buf.append(SELECTED);
            buf.append('>').append(_t("Download, verify, and restart")).append("</option>");
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

    /** @since 0.9.20 */
    public String getDevSU3URL() {
        return _context.getProperty(ConfigUpdateHandler.PROP_DEV_SU3_URL, "");
    }

    public String getNewsStatus() { 
        return NewsHelper.status(_context);
    }
}
