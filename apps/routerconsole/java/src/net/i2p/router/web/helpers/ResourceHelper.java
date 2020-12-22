package net.i2p.router.web.helpers;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.Locale;

import net.i2p.router.web.HelperBase;
import net.i2p.router.web.Messages;

/**
 * @since 0.9.49
 */
public class ResourceHelper extends HelperBase {
    protected String _page;
    private int _maxLines;
    
    /**
     * Use relative path for getResource().
     */
    public void setPage(String page) { _page = page; }

    public void setMaxLines(String lines) {
        if (lines != null) {
            try {
                _maxLines = Integer.parseInt(lines);
            } catch (NumberFormatException nfe) {
                _maxLines = -1;
            }
        } else {
            _maxLines = -1;
        }
    } 

    /**
     * Convert file.ext to file_lang.ext if it exists.
     * Get lang from the cgi lang param, then properties, then from the default locale.
     * _context must be set to check the property.
     * @return "" on error
     */
    public String getResource() {
        if (_page == null || _page.contains(".."))
            return "";
        String lang = null;
        String page = null;
        int lastdot = _page.lastIndexOf('.');
        if (lastdot <= 0) {
            page = _page;
        } else {
            if (_context != null)
                lang = _context.getProperty(Messages.PROP_LANG);
            if (lang == null || lang.length() <= 0) {
                lang = Locale.getDefault().getLanguage();
                if (lang == null || lang.length() <= 0)
                    page = _page;
            }
        }
        if (page == null) {
            if (lang.equals("en"))
                page = _page;
            else
                page = _page.substring(0, lastdot) + '_' + lang + _page.substring(lastdot);
        }
        InputStream is = ResourceHelper.class.getResourceAsStream("/net/i2p/router/web/resources/" + page);
        if (is == null) {
            is = ResourceHelper.class.getResourceAsStream("/net/i2p/router/web/resources/" + _page);
            if (is == null)
                return "";
        }
        BufferedReader in = null;
        StringBuilder buf = new StringBuilder(20000);
        try {
            in = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line = null;
            int i = 0;
            while ( (line = in.readLine()) != null) {
                buf.append(line);
                if (_maxLines > 0 && ++i >= _maxLines)
                    break;
            }
        } catch (IOException ioe) {
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            try { is.close(); } catch (IOException ioe) {}
        }
        return buf.toString();
    }
}
