package org.klomp.snark;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.i2p.data.Base32;

/**
 *
 * @since 0.9.4 moved from I2PSnarkServlet
 */
public class MagnetURI {

    private final String _tracker;
    private final String _name;
    private final byte[] _ih;

    /** BEP 9 */
    public static final String MAGNET = "magnet:";
    public static final String MAGNET_FULL = MAGNET + "?xt=urn:btih:";
    /** http://sponge.i2p/files/maggotspec.txt */
    public static final String MAGGOT = "maggot://";

    /**
     *  @param url non-null
     */
    public MagnetURI(I2PSnarkUtil util, String url) throws IllegalArgumentException {
        String ihash;
        String name;
        String trackerURL = null;
        if (url.startsWith(MAGNET)) {
            // magnet:?xt=urn:btih:0691e40aae02e552cfcb57af1dca56214680c0c5&tr=http://tracker2.postman.i2p/announce.php
            String xt = getParam("xt", url);
            if (xt == null || !xt.startsWith("urn:btih:"))
                throw new IllegalArgumentException();
            ihash = xt.substring("urn:btih:".length());
            trackerURL = getTrackerParam(url);
            name = util.getString("Magnet") + ' ' + ihash;
            String dn = getParam("dn", url);
            if (dn != null)
                name += " (" + Storage.filterName(dn) + ')';
        } else if (url.startsWith(MAGGOT)) {
            // maggot://0691e40aae02e552cfcb57af1dca56214680c0c5:0b557bbdf8718e95d352fbe994dec3a383e2ede7
            ihash = url.substring(MAGGOT.length()).trim();
            int col = ihash.indexOf(':');
            if (col >= 0)
                ihash = ihash.substring(0, col);
            name = util.getString("Magnet") + ' ' + ihash;
        } else {
            throw new IllegalArgumentException();
        }
        byte[] ih = null;
        if (ihash.length() == 32) {
            ih = Base32.decode(ihash);
        } else if (ihash.length() == 40) {
            //  Like DataHelper.fromHexString() but ensures no loss of leading zero bytes
            ih = new byte[20];
            try {
                for (int i = 0; i < 20; i++) {
                    ih[i] = (byte) (Integer.parseInt(ihash.substring(i*2, (i*2) + 2), 16) & 0xff);
                }
            } catch (NumberFormatException nfe) {
                ih = null;
            }
        }
        if (ih == null || ih.length != 20)
            throw new IllegalArgumentException();
        _ih = ih;
        _name = name;
        _tracker = trackerURL;
    }

    /**
     *  @return 20 bytes or null
     */
    public byte[] getInfoHash() {
        return _ih;
    }

    /**
     *  @return pretty name or null
     */
    public String getName() {
        return _name;
    }

    /**
     *  @return tracker url or null
     */
    public String getTrackerURL() {
        return _tracker;
    }

    /**
     *  @return first decoded parameter or null
     */
    private static String getParam(String key, String uri) {
        int idx = uri.indexOf('?' + key + '=');
        if (idx >= 0) {
            idx += key.length() + 2;
        } else {
            idx = uri.indexOf('&' + key + '=');
            if (idx >= 0)
                idx += key.length() + 2;
        }
        if (idx < 0 || idx > uri.length())
            return null;
        String rv = uri.substring(idx);
        idx = rv.indexOf('&');
        if (idx >= 0)
            rv = rv.substring(0, idx);
        else
            rv = rv.trim();
        return decode(rv);
    }

    /**
     *  @return all decoded parameters or null
     *  @since 0.9.1
     */
    private static List<String> getMultiParam(String key, String uri) {
        int idx = uri.indexOf('?' + key + '=');
        if (idx >= 0) {
            idx += key.length() + 2;
        } else {
            idx = uri.indexOf('&' + key + '=');
            if (idx >= 0)
                idx += key.length() + 2;
        }
        if (idx < 0 || idx > uri.length())
            return null;
        List<String> rv = new ArrayList<String>();
        while (true) {
            String p = uri.substring(idx);
            uri = p;
            idx = p.indexOf('&');
            if (idx >= 0)
                p = p.substring(0, idx);
            else
                p = p.trim();
            rv.add(decode(p));
            idx = uri.indexOf('&' + key + '=');
            if (idx < 0)
                break;
            idx += key.length() + 2;
        }
        return rv;
    }

    /**
     *  @return first valid I2P tracker or null
     *  @since 0.9.1
     */
    private static String getTrackerParam(String uri) {
        List<String> trackers = getMultiParam("tr", uri);
        if (trackers == null)
            return null;
        for (String t : trackers) {
            try {
                URI u = new URI(t);
                String protocol = u.getScheme();
                String host = u.getHost();
                if (protocol == null || host == null ||
                    !protocol.toLowerCase(Locale.US).equals("http") ||
                    !host.toLowerCase(Locale.US).endsWith(".i2p"))
                    continue;
                return t;
            } catch(URISyntaxException use) {}
        }
        return null;
    }

    /**
     *  Decode %xx encoding, convert to UTF-8 if necessary
     *  Copied from i2ptunnel LocalHTTPServer
     *  @since 0.9.1
     */
    private static String decode(String s) {
        if (!s.contains("%"))
            return s;
        StringBuilder buf = new StringBuilder(s.length());
        boolean utf8 = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '%') {
                buf.append(c);
            } else {
                try {
                    int val = Integer.parseInt(s.substring(++i, (++i) + 1), 16);
                    if ((val & 0x80) != 0)
                        utf8 = true;
                    buf.append((char) val);
                } catch (IndexOutOfBoundsException ioobe) {
                    break;
                } catch (NumberFormatException nfe) {
                    break;
                }
            }
        }
        if (utf8) {
            try {
                return new String(buf.toString().getBytes("ISO-8859-1"), "UTF-8");
            } catch (UnsupportedEncodingException uee) {}
        }
        return buf.toString();
    }

}
