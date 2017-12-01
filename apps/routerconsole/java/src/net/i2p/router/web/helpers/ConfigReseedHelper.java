package net.i2p.router.web.helpers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;

import net.i2p.router.networkdb.reseed.Reseeder;
import net.i2p.router.web.HelperBase;

/**
 *  @since 0.8.3
 */
public class ConfigReseedHelper extends HelperBase {

    public String getPort() {
        return _context.getProperty(Reseeder.PROP_PROXY_PORT, "");
    }

    public String getHost() {
        return _context.getProperty(Reseeder.PROP_PROXY_HOST, "");
    }

    /** @since 0.8.9 */
    public String getUsername() {
        return _context.getProperty(Reseeder.PROP_PROXY_USERNAME, "");
    }

    /** @since 0.8.9 */
    public String getNofilter_password() {
        return _context.getProperty(Reseeder.PROP_PROXY_PASSWORD, "");
    }

    /** @since 0.8.9 */
    public String getSport() {
        return _context.getProperty(Reseeder.PROP_SPROXY_PORT, "");
    }

    /** @since 0.8.9 */
    public String getShost() {
        return _context.getProperty(Reseeder.PROP_SPROXY_HOST, "");
    }

    /** @since 0.8.9 */
    public String getSusername() {
        return _context.getProperty(Reseeder.PROP_SPROXY_USERNAME, "");
    }

    /** @since 0.8.9 */
    public String getNofilter_spassword() {
        return _context.getProperty(Reseeder.PROP_SPROXY_PASSWORD, "");
    }

    public String modeChecked(int mode) {
        boolean required =  _context.getBooleanPropertyDefaultTrue(Reseeder.PROP_SSL_REQUIRED);
        boolean disabled =  _context.getBooleanProperty(Reseeder.PROP_SSL_DISABLE);
        if ((mode == 0 && (!disabled) && (!required)) ||
            (mode == 1 && (!disabled) && required) ||
            (mode == 2 && disabled))
            return CHECKED;
        return "";
    }

    /** @since 0.9.33 */
    public String pmodeChecked(int mode) {
        String c =  _context.getProperty(Reseeder.PROP_SPROXY_TYPE, "HTTP");
        boolean disabled =  !_context.getBooleanProperty(Reseeder.PROP_SPROXY_ENABLE);
        if ((mode == 0 && disabled) ||
            (mode == 1 && !disabled && c.equals("HTTP")) ||
            (mode == 2 && !disabled && c.equals("SOCKS4")) ||
            (mode == 3 && !disabled && c.equals("SOCKS5")) ||
            (mode == 4 && !disabled && c.equals("INTERNAL")))
            return CHECKED;
        return "";
    }

    public String getEnable() {
        return getChecked(Reseeder.PROP_PROXY_ENABLE);
    }

    /** @since 0.8.9 */
    public String getAuth() {
        return getChecked(Reseeder.PROP_PROXY_AUTH_ENABLE);
    }

/****
    public String getSenable() {
        return getChecked(Reseeder.PROP_SPROXY_ENABLE);
    }
****/

    /** @since 0.8.9 */
    public String getSauth() {
        return getChecked(Reseeder.PROP_SPROXY_AUTH_ENABLE);
    }

    private List<String> reseedList() {
        String urls = _context.getProperty(Reseeder.PROP_RESEED_URL, Reseeder.DEFAULT_SEED_URL + ',' + Reseeder.DEFAULT_SSL_SEED_URL);
        StringTokenizer tok = new StringTokenizer(urls, " ,\r\n");
        List<String> URLList = new ArrayList<String>(16);
        while (tok.hasMoreTokens()) {
            String s = tok.nextToken().trim();
            if (s.length() > 0)
                URLList.add(s);
        }
        return URLList;
    }

    public String getReseedURL() {
        List<String> URLList = reseedList();
        Collections.sort(URLList);
        StringBuilder buf = new StringBuilder();
        for (String s : URLList) {
             if (buf.length() > 0)
                 buf.append('\n');
             buf.append(s);
        }
        return buf.toString();
    }

    /**
     *  @return true only if we have both http and https URLs
     *  @since 0.9.33
     */
    public boolean shouldShowSelect() {
        boolean http = false;
        boolean https = false;
        for (String u : reseedList()) {
            if (u.startsWith("https://")) {
                if (http)
                    return true;
                https = true;
            } else if (u.startsWith("http://")) {
                if (https)
                    return true;
                http = true;
            }
        }
        return false;
    }

    /**
     *  @return true only if we have a http URL
     *  @since 0.9.33
     */
    public boolean shouldShowHTTPProxy() {
        for (String u : reseedList()) {
            if (u.startsWith("http://"))
                return true;
        }
        return false;
    }

    /**
     *  @return true only if we have a https URL
     *  @since 0.9.33
     */
    public boolean shouldShowHTTPSProxy() {
        for (String u : reseedList()) {
            if (u.startsWith("https://"))
                return true;
        }
        return false;
    }
}
