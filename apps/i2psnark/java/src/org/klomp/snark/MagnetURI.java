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

    private final List<String> _trackers;
    private final String _name;
    private final byte[] _ih;

    /** BEP 9 */
    public static final String MAGNET = "magnet:";
    public static final String MAGNET_FULL = MAGNET + "?xt=urn:btih:";
    /** http://sponge.i2p/files/maggotspec.txt */
    public static final String MAGGOT = "maggot://";
    /**
     *  https://blog.libtorrent.org/2020/09/bittorrent-v2/
     *  TODO, dup param parsing, as a dual v1/v2 link
     *  will contain two xt params
     *  @since 0.9.48
     */
    public static final String MAGNET_FULL_V2 = MAGNET + "?xt=urn:btmh:";

    /**
     *  @param url non-null
     */
    public MagnetURI(I2PSnarkUtil util, String url) throws IllegalArgumentException {
        String ihash;
        String name;
        List<String> trackerURLs = null;
        if (url.startsWith(MAGNET)) {
            // magnet:?xt=urn:btih:0691e40aae02e552cfcb57af1dca56214680c0c5&tr=http://tracker2.postman.i2p/announce.php
            String xt = getParam("xt", url);
            // TODO btmh
            if (xt == null || !xt.startsWith("urn:btih:"))
                throw new IllegalArgumentException();
            ihash = xt.substring("urn:btih:".length());
            trackerURLs = getTrackerParam(url);
            name = util.getString("Magnet") + ' ' + ihash;
            String dn = getParam("dn", url);
            if (dn != null)
                name += " (" + dn + ')';
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
        _trackers = trackerURLs;
    }

    /**
     *  @return 20 bytes or null
     */
    public byte[] getInfoHash() {
        return _ih;
    }

    /**
     *  @return pretty name or null, NOT HTML escaped
     */
    public String getName() {
        return _name;
    }

    /**
     *  @return first valid tracker url or null
     */
    public String getTrackerURL() {
        return _trackers != null ? _trackers.get(0) : null;
    }

    /**
     *  @return all valid tracker urls or null if none
     *  @since 0.9.67 TODO to be hooked in via SnarkManager.addMagnet() and new Snark()
     */
    public List<String> getTrackerURLs() {
        return _trackers;
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
     *  @return all valid I2P trackers or null if none
     *  @since 0.9.1
     */
    private static List<String> getTrackerParam(String uri) {
        List<String> trackers = getMultiParam("tr", uri);
        if (trackers == null)
            return null;
        List<String> rv = new ArrayList<String>(trackers.size());
        for (String t : trackers) {
            try {
                URI u = new URI(t);
                String protocol = u.getScheme();
                String host = u.getHost();
                if (protocol == null || host == null)
                    continue;
                protocol = protocol.toLowerCase(Locale.US);
                if (!(protocol.equals("http") || protocol.equals("udp")) ||
                    !host.toLowerCase(Locale.US).endsWith(".i2p"))
                    continue;
                rv.add(t);
            } catch(URISyntaxException use) {}
        }
        return rv.isEmpty() ? null : rv;
    }

    /**
     *  Decode %xx encoding, convert to UTF-8 if necessary.
     *  Copied from i2ptunnel LocalHTTPServer.
     *  Also converts '+' to ' ' so the dn parameter comes out right
     *  These are coming in via a application/x-www-form-urlencoded form so
     *  the pluses are in there...
     *  hopefully any real + is encoded as %2B.
     *
     *  @since 0.9.1
     */
    private static String decode(String s) {
        if (!(s.contains("%") || s.contains("+")))
            return s;
        StringBuilder buf = new StringBuilder(s.length());
        boolean utf8 = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '+') {
                buf.append(' ');
            } else if (c != '%') {
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
