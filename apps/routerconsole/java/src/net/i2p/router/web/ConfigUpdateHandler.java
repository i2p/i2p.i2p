package net.i2p.router.web;

import net.i2p.data.DataHelper;

/**
 *
 */
public class ConfigUpdateHandler extends FormHandler {
    private String _newsURL;
    private long _refreshFrequency;
    private String _updateURL;
    private String _updatePolicy;
    private boolean _updateThroughProxy;
    private String _trustedKeys;

    public static final String PROP_NEWS_URL = "router.newsURL";
    public static final String DEFAULT_NEWS_URL = "http://www.i2p/routerConsoleNews.xml";
    public static final String PROP_REFRESH_FREQUENCY = "router.newsRefreshFrequency";
    public static final String DEFAULT_REFRESH_FREQUENCY = 24*60*60*1000 + "";
    public static final String PROP_UPDATE_URL = "router.updateURL";
    public static final String DEFAULT_UPDATE_URL = "http://dev.i2p.net/i2p/i2pupdate.sud";
    public static final String PROP_UPDATE_POLICY = "router.updatePolicy";
    public static final String DEFAULT_UPDATE_POLICY = "notify";
    public static final String PROP_SHOULD_PROXY = "router.updateThroughProxy";
    public static final String DEFAULT_SHOULD_PROXY = Boolean.FALSE.toString();
    public static final String PROP_PROXY_HOST = "router.updateProxyHost";
    public static final String DEFAULT_PROXY_HOST = "localhost";
    public static final String PROP_PROXY_PORT = "router.updateProxyPort";
    public static final String DEFAULT_PROXY_PORT = "4444";
    
    protected void processForm() {
        if ( (_newsURL != null) && (_newsURL.length() > 0) ) {
            String oldURL = _context.router().getConfigSetting(PROP_NEWS_URL);
            if ( (oldURL == null) || (!_newsURL.equals(oldURL)) ) {
                _context.router().setConfigSetting(PROP_NEWS_URL, _newsURL);
                addFormNotice("Updating news URL to " + _newsURL);
            }
        }
        if ( (_updateURL != null) && (_updateURL.length() > 0) ) {
            String oldURL = _context.router().getConfigSetting(PROP_UPDATE_URL);
            if ( (oldURL == null) || (!_updateURL.equals(oldURL)) ) {
                _context.router().setConfigSetting(PROP_UPDATE_URL, _updateURL);
                addFormNotice("Updating update URL to " + _updateURL);
            }
        }
        
        if (_updateThroughProxy) {
            _context.router().setConfigSetting(PROP_SHOULD_PROXY, Boolean.TRUE.toString());
        } else {
            _context.router().setConfigSetting(PROP_SHOULD_PROXY, Boolean.FALSE.toString());
        }
        
        String oldFreqStr = _context.router().getConfigSetting(PROP_REFRESH_FREQUENCY);
        long oldFreq = -1;
        if (oldFreqStr != null) 
            try { oldFreq = Long.parseLong(oldFreqStr); } catch (NumberFormatException nfe) {}
        if (_refreshFrequency != oldFreq) {
            _context.router().setConfigSetting(PROP_REFRESH_FREQUENCY, ""+_refreshFrequency);
            addFormNotice("Updating refresh frequency to " + DataHelper.formatDuration(_refreshFrequency));
        }

        if ( (_updatePolicy != null) && (_updatePolicy.length() > 0) ) {
            String oldPolicy = _context.router().getConfigSetting(PROP_UPDATE_POLICY);
            if ( (oldPolicy == null) || (!_updatePolicy.equals(oldPolicy)) ) {
                _context.router().setConfigSetting(PROP_UPDATE_POLICY, _updatePolicy);
                addFormNotice("Updating update policy to " + _updatePolicy);
            }
        }
        
        // should save the keys...
        
        _context.router().saveConfig();
    }
    
    public void setNewsURL(String url) { _newsURL = url; }
    public void setRefreshFrequency(String freq) {
        try { _refreshFrequency = Long.parseLong(freq); } catch (NumberFormatException nfe) {}
    }
    public void setUpdateURL(String url) { _updateURL = url; }
    public void setUpdatePolicy(String policy) { _updatePolicy = policy; }
    public void setTrustedKeys(String keys) { _trustedKeys = keys; }
    public void setUpdateThroughProxy(String foo) { _updateThroughProxy = true; }
}
