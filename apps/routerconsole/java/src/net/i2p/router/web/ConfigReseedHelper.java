package net.i2p.router.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;

import net.i2p.router.networkdb.reseed.Reseeder;

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
    public String getPassword() {
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
    public String getSpassword() {
        return _context.getProperty(Reseeder.PROP_SPROXY_PASSWORD, "");
    }

    public String modeChecked(int mode) {
        boolean required =  _context.getBooleanProperty(Reseeder.PROP_SSL_REQUIRED);
        boolean disabled =  _context.getBooleanProperty(Reseeder.PROP_SSL_DISABLE);
        if ((mode == 0 && (!disabled) && (!required)) ||
            (mode == 1 && (!disabled) && required) ||
            (mode == 2 && disabled))
            return "checked=\"true\"";
        return "";
    }

    public String getEnable() {
        return checked(Reseeder.PROP_PROXY_ENABLE);
    }

    /** @since 0.8.9 */
    public String getAuth() {
        return checked(Reseeder.PROP_PROXY_AUTH_ENABLE);
    }

    public String getSenable() {
        return checked(Reseeder.PROP_SPROXY_ENABLE);
    }

    /** @since 0.8.9 */
    public String getSauth() {
        return checked(Reseeder.PROP_SPROXY_AUTH_ENABLE);
    }

    /** @since 0.8.9 */
    private String checked(String prop) {
        boolean enabled =  _context.getBooleanProperty(prop);
        if (enabled)
            return "checked=\"true\"";
        return "";
    }

    public String getReseedURL() {
        String urls = _context.getProperty(Reseeder.PROP_RESEED_URL, Reseeder.DEFAULT_SEED_URL + ',' + Reseeder.DEFAULT_SSL_SEED_URL);
        StringTokenizer tok = new StringTokenizer(urls, " ,\r\n");
        List<String> URLList = new ArrayList(16);
        while (tok.hasMoreTokens()) {
            String s = tok.nextToken().trim();
            if (s.length() > 0)
                URLList.add(s);
        }
        Collections.sort(URLList);
        StringBuilder buf = new StringBuilder();
        for (String s : URLList) {
             if (buf.length() > 0)
                 buf.append('\n');
             buf.append(s);
        }
        return buf.toString();
    }
}
