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
        boolean enabled =  _context.getBooleanProperty(Reseeder.PROP_PROXY_ENABLE);
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
