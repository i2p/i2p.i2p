package net.i2p.router.web;

import net.i2p.I2PAppContext;
import net.i2p.crypto.TrustedUpdate;
import net.i2p.data.DataHelper;

/**
 *
 */
public class ConfigUpdateHandler extends FormHandler {
    private String _newsURL;
    private long _refreshFrequency;
    private String _updateURL;
    private String _updatePolicy;
    private String _proxyHost;
    private String _proxyPort;
    private boolean _updateThroughProxy;
    private String _trustedKeys;
    private boolean _updateUnsigned;
    private String _zipURL;

    public static final String PROP_NEWS_URL = "router.newsURL";
//  public static final String DEFAULT_NEWS_URL = "http://dev.i2p.net/cgi-bin/cvsweb.cgi/i2p/news.xml?rev=HEAD";
    public static final String OLD_DEFAULT_NEWS_URL = "http://complication.i2p/news.xml";
    public static final String DEFAULT_NEWS_URL = "http://echelon.i2p/i2p/news.xml";
    public static final String PROP_REFRESH_FREQUENCY = "router.newsRefreshFrequency";
    public static final String DEFAULT_REFRESH_FREQUENCY = 24*60*60*1000 + "";
    public static final String PROP_UPDATE_POLICY = "router.updatePolicy";
    public static final String DEFAULT_UPDATE_POLICY = "download";
    public static final String PROP_SHOULD_PROXY = "router.updateThroughProxy";
    public static final String DEFAULT_SHOULD_PROXY = Boolean.TRUE.toString();
    public static final String PROP_PROXY_HOST = "router.updateProxyHost";
    public static final String DEFAULT_PROXY_HOST = "127.0.0.1";
    public static final String PROP_PROXY_PORT = "router.updateProxyPort";
    public static final int DEFAULT_PROXY_PORT_INT = 4444;
    public static final String DEFAULT_PROXY_PORT = "" + DEFAULT_PROXY_PORT_INT;
    /** default false */
    public static final String PROP_UPDATE_UNSIGNED = "router.updateUnsigned";
    /** default false - use for distros */
    public static final String PROP_UPDATE_DISABLED = "router.updateDisabled";
    /** no default */
    public static final String PROP_ZIP_URL = "router.updateUnsignedURL";
    
    public static final String PROP_UPDATE_URL = "router.updateURL";
    /**
     *  Changed as of release 0.8 to support both .sud and .su2
     *  Some JVMs (IcedTea) don't have pack200
     *  Update hosts must maintain both
     */
    private static final String PACK200_URLS =
    "http://echelon.i2p/i2p/i2pupdate.su2\r\n" +
    "http://stats.i2p/i2p/i2pupdate.su2\r\n" +
    "http://www.i2p2.i2p/_static/i2pupdate.su2\r\n" +
    "http://update.postman.i2p/i2pupdate.su2" ;

    private static final String NO_PACK200_URLS =
    "http://echelon.i2p/i2p/i2pupdate.sud\r\n" +
    "http://stats.i2p/i2p/i2pupdate.sud\r\n" +
    "http://www.i2p2.i2p/_static/i2pupdate.sud\r\n" +
    "http://update.postman.i2p/i2pupdate.sud" ;

    public static final String DEFAULT_UPDATE_URL;
    static {
        String foo;
        try {
            Class.forName("java.util.jar.Pack200", false, ClassLoader.getSystemClassLoader());
            foo = PACK200_URLS;
        } catch (ClassNotFoundException cnfe) {
            try {
                Class.forName("org.apache.harmony.unpack200.Archive", false, ClassLoader.getSystemClassLoader());
                foo = PACK200_URLS;
            } catch (ClassNotFoundException cnfe2) {
                foo = NO_PACK200_URLS;
            }
        }
        DEFAULT_UPDATE_URL = foo;
    }

    public static final String PROP_TRUSTED_KEYS = "router.trustedUpdateKeys";
    
    
    @Override
    protected void processForm() {
        if (_action == null)
            return;
        if (_action.equals(_("Check for updates"))) {
            NewsFetcher fetcher = NewsFetcher.getInstance(I2PAppContext.getGlobalContext());
            fetcher.fetchNews();
            if (fetcher.shouldFetchUnsigned())
                fetcher.fetchUnsignedHead();
            if (fetcher.updateAvailable() || fetcher.unsignedUpdateAvailable()) {
                if ( (_updatePolicy == null) || (!_updatePolicy.equals("notify")) )
                    addFormNotice(_("Update available, attempting to download now"));
                else
                    addFormNotice(_("Update available, click button on left to download"));
                // So that update() will post a status to the summary bar before we reload
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {}
            } else
                addFormNotice(_("No update available"));
            return;
        }

        if ( (_newsURL != null) && (_newsURL.length() > 0) ) {
            String oldURL = ConfigUpdateHelper.getNewsURL(_context);
            if ( (oldURL == null) || (!_newsURL.equals(oldURL)) ) {
                _context.router().setConfigSetting(PROP_NEWS_URL, _newsURL);
                addFormNotice(_("Updating news URL to") + " " + _newsURL);
            }
        }
        
        if ( (_proxyHost != null) && (_proxyHost.length() > 0) ) {
            String oldHost = _context.router().getConfigSetting(PROP_PROXY_HOST);
            if ( (oldHost == null) || (!_proxyHost.equals(oldHost)) ) {
                _context.router().setConfigSetting(PROP_PROXY_HOST, _proxyHost);
                addFormNotice(_("Updating proxy host to") + " " + _proxyHost);
            }
        }
        
        if ( (_proxyPort != null) && (_proxyPort.length() > 0) ) {
            String oldPort = _context.router().getConfigSetting(PROP_PROXY_PORT);
            if ( (oldPort == null) || (!_proxyPort.equals(oldPort)) ) {
                _context.router().setConfigSetting(PROP_PROXY_PORT, _proxyPort);
                addFormNotice(_("Updating proxy port to") + " " + _proxyPort);
            }
        }
        
        _context.router().setConfigSetting(PROP_SHOULD_PROXY, "" + _updateThroughProxy);
        _context.router().setConfigSetting(PROP_UPDATE_UNSIGNED, "" + _updateUnsigned);
        
        String oldFreqStr = _context.router().getConfigSetting(PROP_REFRESH_FREQUENCY);
        long oldFreq = -1;
        if (oldFreqStr != null) 
            try { oldFreq = Long.parseLong(oldFreqStr); } catch (NumberFormatException nfe) {}
        if (_refreshFrequency != oldFreq) {
            _context.router().setConfigSetting(PROP_REFRESH_FREQUENCY, ""+_refreshFrequency);
            addFormNotice(_("Updating refresh frequency to") + " " + DataHelper.formatDuration2(_refreshFrequency));
        }

        if ( (_updatePolicy != null) && (_updatePolicy.length() > 0) ) {
            String oldPolicy = _context.router().getConfigSetting(PROP_UPDATE_POLICY);
            if ( (oldPolicy == null) || (!_updatePolicy.equals(oldPolicy)) ) {
                _context.router().setConfigSetting(PROP_UPDATE_POLICY, _updatePolicy);
                addFormNotice(_("Updating update policy to") + " " + _updatePolicy);
            }
        }

        if ( (_updateURL != null) && (_updateURL.length() > 0) ) {
            _updateURL = _updateURL.replace("\r\n", ",").replace("\n", ",");
            String oldURL = _context.router().getConfigSetting(PROP_UPDATE_URL);
            if ( (oldURL == null) || (!_updateURL.equals(oldURL)) ) {
                _context.router().setConfigSetting(PROP_UPDATE_URL, _updateURL);
                addFormNotice(_("Updating update URLs."));
            }
        }

        if ( (_trustedKeys != null) && (_trustedKeys.length() > 0) ) {
            _trustedKeys = _trustedKeys.replace("\r\n", ",").replace("\n", ",");
            String oldKeys = new TrustedUpdate(_context).getTrustedKeysString();
            if ( (oldKeys == null) || (!_trustedKeys.equals(oldKeys)) ) {
                _context.router().setConfigSetting(PROP_TRUSTED_KEYS, _trustedKeys);
                addFormNotice(_("Updating trusted keys."));
            }
        }
        
        if ( (_zipURL != null) && (_zipURL.length() > 0) ) {
            String oldURL = _context.router().getConfigSetting(PROP_ZIP_URL);
            if ( (oldURL == null) || (!_zipURL.equals(oldURL)) ) {
                _context.router().setConfigSetting(PROP_ZIP_URL, _zipURL);
                addFormNotice(_("Updating unsigned update URL to") + " " + _zipURL);
            }
        }
        
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
    public void setProxyHost(String host) { _proxyHost = host; }
    public void setProxyPort(String port) { _proxyPort = port; }
    public void setUpdateUnsigned(String foo) { _updateUnsigned = true; }
    public void setZipURL(String url) { _zipURL = url; }
}
