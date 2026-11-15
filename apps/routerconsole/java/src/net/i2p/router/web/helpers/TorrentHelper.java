package net.i2p.router.web.helpers;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;

import net.i2p.servlet.filters.XSSRequestWrapper;

/** Links from the console and other local apps to Snark's add-torrent form. */
public final class TorrentHelper {
    private TorrentHelper() {}

    public static String getSnarkURLFromRequest(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod()))
            return getSnarkURL(null);
        String url = request.getParameter("nofilter_newURL");
        if (url == null || url.trim().length() == 0) {
            // The legacy name is filtered. Read it only for the encoded form link.
            ServletRequest original = request instanceof XSSRequestWrapper ?
                    ((XSSRequestWrapper) request).getRequest() : request;
            url = original.getParameter("newURL");
        }
        return getSnarkURL(url);
    }

    /**
     * Prefill the form for user review. This link never starts a download.
     * Only the supplied URL is forwarded; actions and form nonces are not copied.
     */
    public static String getSnarkURL(String url) {
        if (url == null || url.trim().length() == 0)
            return "/i2psnark/";
        try {
            return "/i2psnark/?nofilter_newURL=" + URLEncoder.encode(url.trim(), "UTF-8") + "#add";
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
