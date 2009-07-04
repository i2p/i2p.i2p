package net.i2p.desktopgui.router.configuration;

import net.i2p.desktopgui.router.RouterHelper;

/**
 *
 * @author mathias
 */
public class UpdateHelper {

    public static final String PROP_NEWS_URL = "router.newsURL";
    public static final String DEFAULT_NEWS_URL = "http://echelon.i2p/i2p/news.xml";

    public static final String PROP_REFRESH_FREQUENCY = "router.newsRefreshFrequency";
    public static final String DEFAULT_REFRESH_FREQUENCY = 24*60*60*1000 + "";

    public static final String PROP_UPDATE_POLICY = "router.updatePolicy";
    public static final String NOTIFY_UPDATE_POLICY = "notify";
    public static final String DOWNLOAD_UPDATE_POLICY = "download";
    public static final String INSTALL_UPDATE_POLICY = "install";
    public static final String DEFAULT_UPDATE_POLICY = DOWNLOAD_UPDATE_POLICY;

    public static final String PROP_SHOULD_PROXY = "router.updateThroughProxy";
    public static final String DEFAULT_SHOULD_PROXY = Boolean.TRUE.toString();
    public static final String PROP_PROXY_HOST = "router.updateProxyHost";
    public static final String DEFAULT_PROXY_HOST = "127.0.0.1";
    public static final String PROP_PROXY_PORT = "router.updateProxyPort";
    public static final String DEFAULT_PROXY_PORT = "4444";

    public static final String PROP_UPDATE_URL = "router.updateURL";
    public static final String DEFAULT_UPDATE_URL =
    "http://echelon.i2p/i2p/i2pupdate.sud\r\n" +
    "http://stats.i2p/i2p/i2pupdate.sud\r\n" +
    "http://www.i2p2.i2p/_static/i2pupdate.sud\r\n" +
    "http://update.postman.i2p/i2pupdate.sud" ;

    public static final String PROP_TRUSTED_KEYS = "router.trustedUpdateKeys";

    public static String getNewsURL() {
        String url = RouterHelper.getContext().getProperty(PROP_NEWS_URL);
        if(url == null) {
            return DEFAULT_NEWS_URL;
        }
        else {
            return url;
        }
    }

    public static String getUpdatePolicy() {
        String policy = null;
        try {
            policy = RouterHelper.getContext().getProperty(PROP_UPDATE_POLICY);
        }
        catch(Exception e) {
            e.printStackTrace();
        }
        System.out.println("Policy: " + policy);
        if(policy == null) {
            return DEFAULT_UPDATE_POLICY;
        }
        else {
            return policy;
        }
    }
}
