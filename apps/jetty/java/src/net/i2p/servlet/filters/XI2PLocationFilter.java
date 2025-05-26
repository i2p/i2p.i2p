package net.i2p.servlet.filters;

import java.io.IOException;
import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.ee8.nested.Request;
import org.eclipse.jetty.ee8.nested.HandlerWrapper;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.PrivateKeyFile;


import net.i2p.util.Log;

/**
 * Adds a header, X-I2P-Location, to requests when they do **not** come in on an I2P hostname.
 * This header contains a URL that looks like: [scheme://][i2phostname.i2p][/path][?query]
 * and expresses the I2P-Equivalent URL of the clearnet query. Clients can use this to prompt
 * users to switch from a non-I2P host to an I2P host or to redirect them automatically. It
 * automatically enabled on the default I2P site located on port 7658 by default.
 *
 *  @since 0.9.51
 */
public class XI2PLocationFilter extends HandlerWrapper {
    private String X_I2P_Location = null;
    private long lastFailure = -1;
    private static final long failTimeout = 600000;
    private static final String encodeUTF = StandardCharsets.UTF_8.toString();
    private final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(XI2PLocationFilter.class);


    private synchronized void setLocation(String xi2plocation) {
        if (_log.shouldInfo())
            _log.info("Checking X-I2P-Location header prefix" + xi2plocation);
        if (X_I2P_Location != null)
            return ;
        if (xi2plocation == null)
            return ;
        if (xi2plocation.equals(""))
            return ;
        X_I2P_Location = xi2plocation;
        if (_log.shouldInfo())
            _log.info("Caching X-I2P-Location header prefix" + X_I2P_Location);
    }

    private synchronized boolean shouldRecheck(){
        boolean settable = (X_I2P_Location == null);
        if (!settable) return settable;
        if (lastFailure == -1) {
            lastFailure = System.currentTimeMillis();
            if (_log.shouldDebug())
                _log.debug("New instance, attempting to set X-I2P-Location header for the first time");
            return settable;
        }
        if ((System.currentTimeMillis() - lastFailure) > failTimeout){
            lastFailure = System.currentTimeMillis();
            if (_log.shouldDebug())
                _log.debug("More than ten minutes since failing attempt to re-check X-I2P-Location header");
            return settable;
        }
        if (_log.shouldDebug())
            _log.debug("Not attempting to re-check X-I2P-Location header");
        return false;
    }

    private synchronized String getXI2PLocation(String host, String port) {
        File configDir = I2PAppContext.getGlobalContext().getConfigDir();
        File tunnelConfig = new File(configDir, "i2ptunnel.config");
        boolean isSingleFile = tunnelConfig.exists();
        if (!isSingleFile) {
            File tunnelConfigD = new File(configDir, "i2ptunnel.config.d");
            File[] configFiles = tunnelConfigD.listFiles(new net.i2p.util.FileSuffixFilter(".config"));
            if (configFiles == null)
                return null;
            for (int fnum=0; fnum < configFiles.length; fnum++) {
                Properties tunnelProps = new Properties();
                try {
                    DataHelper.loadProps(tunnelProps, configFiles[fnum]);
                    String targetHost = tunnelProps.getProperty("targetHost");
                    boolean hostmatch = (host.equals(targetHost) || "0.0.0.0".equals(targetHost) || "::".equals(targetHost));
                    if ( hostmatch && port.equals(tunnelProps.getProperty("targetPort")) ) {
                        String sh = tunnelProps.getProperty("spoofedHost");
                        if (sh != null) {
                            if (sh.endsWith(".i2p"))
                                return sh;
                        }
                        String kf = tunnelProps.getProperty("privKeyFile");
                        if (kf != null) {
                            File keyFile = new File(kf);
                            if (!keyFile.isAbsolute())
                                keyFile = new File(configDir, kf);
                            if (keyFile.exists()) {
                                PrivateKeyFile pkf = new PrivateKeyFile(keyFile);
                                try {
                                    Destination rv = pkf.getDestination();
                                    if (rv != null)
                                        return rv.toBase32();
                                } catch (I2PException e) {
                                    if (_log.shouldWarn())
                                        _log.warn("I2PException Unable to set X-I2P-Location, keys arent ready. This is probably safe to ignore and will go away after the first run." + e);
                                    return null;
                                } catch (IOException e) {
                                    if (_log.shouldWarn())
                                        _log.warn("IOE Unable to set X-I2P-Location, location is uninitialized due file not found. This probably means the keys aren't ready. This is probably safe to ignore." + e);
                                    return null;
                                }
                            }
                        }
                        if (_log.shouldWarn())
                            _log.warn("Unable to set X-I2P-Location, location is target not found in any I2PTunnel config file. This should never happen.");
                        return null;
                    }
                } catch (IOException ioe) {
                    if (_log.shouldWarn())
                        _log.warn("IOE Unable to set X-I2P-Location, location is uninitialized. This is probably safe to ignore. location='" + ioe + "'");
                    return null;
                }
            }
        } else {
            // don't bother
        }
        return null;
    }

    private synchronized String headerContents(final HttpServletRequest httpRequest) {
        if (X_I2P_Location != null) {
            String scheme = httpRequest.getScheme();
            if (scheme == null)
                 scheme = "";
            String path = httpRequest.getPathInfo();
            if (path == null)
                path = "";
            String query = httpRequest.getQueryString();
            if (query == null)
                query = "";
            try {
                if (query.equals("")) {
                    URI uri = new URI(scheme, X_I2P_Location, path, null);
                    String encodedURL = uri.toASCIIString();
                    return encodedURL;
                } else {
                    URI uri = new URI(scheme, X_I2P_Location, path, query, null);
                    String encodedURL = uri.toASCIIString();
                    return encodedURL;
                }
            }catch(URISyntaxException use){
                return null;
            }
        }
        return null;
    }

    @Override
    public void handle(final String target, final Request request, final HttpServletRequest httpRequest, HttpServletResponse httpResponse)
    throws IOException, ServletException {
        final String hashHeader = httpRequest.getHeader("X-I2P-DestHash");

        if (hashHeader == null) {
            if (shouldRecheck()) {
                String xi2plocation = getXI2PLocation(request.getLocalAddr(), String.valueOf(request.getLocalPort()));
                if (_log.shouldInfo())
                   _log.info("Checking X-I2P-Location header IP " + request.getLocalAddr() + " port " + request.getLocalPort() + " prefix " + xi2plocation);
                setLocation(xi2plocation);
            }
            String headerURL = headerContents(httpRequest);
            if (headerURL != null) {
                if (_log.shouldInfo())
                    _log.info("Checking X-I2P-Location header" + headerURL);
                httpResponse.addHeader("X-I2P-Location", headerURL);
            }
        }

        _handler.handle(target, request, httpRequest, httpResponse);
    }
}
