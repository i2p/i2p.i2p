/*
 * I2P - An anonymous, secure, and fully-distributed communication network.
 * 
 * UrlLauncher.java
 * 2004 The I2P Project
 * http://www.i2p.net
 * This code is public domain.
 */

package net.i2p.apps.systray;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.i2p.I2PAppContext;
import net.i2p.app.*;
import static net.i2p.app.ClientAppState.*;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.ShellCommand;
import net.i2p.util.SystemVersion;

/**
 * A quick and simple multi-platform URL launcher. It attempts to launch the
 * default browser for the host platform first, then popular third-party
 * browsers if that was not successful.
 * <p>
 * Handles Galeon, Internet Explorer, Konqueror, Links, Lynx, Mozilla, Mozilla
 * Firefox, Netscape, Opera, and Safari.    
 * 
 * @author hypercubus
 */
public class UrlLauncher implements ClientApp {

    private final ShellCommand _shellCommand;
    private volatile ClientAppState _state;
    private final I2PAppContext _context;
    private final ClientAppManager _mgr;
    private final String[] _args;
    private final Log _log;

    private static final int WAIT_TIME = 5*1000;
    private static final int MAX_WAIT_TIME = 5*60*1000;
    private static final int MAX_TRIES = 99;
    private static final String REGISTERED_NAME = "UrlLauncher";
    private static final String PROP_BROWSER = "routerconsole.browser";

    /**
     *  Browsers to try IN-ORDER
     */
    private static final String[] BROWSERS = {
            // This debian script tries everything in $BROWSER, then gnome-www-browser and x-www-browser
            // if X is running and www-browser otherwise. Those point to the user's preferred
            // browser using the update-alternatives system.
            "sensible-browser",
            // another one that opens a preferred browser
            "xdg-open",
            // Try x-www-browser directly
            "x-www-browser",
            // general graphical browsers
            "defaultbrowser",  // puppy linux
            "opera -newpage",
            "firefox",
            "chromium-browser",
            "mozilla",
            "netscape",
            "konqueror",
            "galeon",
            // Text Mode Browsers only below here
            "www-browser",
            "links",
            "lynx"
    };
            
    /**
     *  ClientApp constructor used from clients.config
     *
     *  @param mgr null OK
     *  @param args URL in args[0] or null args for router console
     *  @since 0.9.18
     */
    public UrlLauncher(I2PAppContext context, ClientAppManager mgr, String[] args) {
        _state = UNINITIALIZED;
        _context = context;
        _log = _context.logManager().getLog(UrlLauncher.class);
        _mgr = mgr;
        if (args == null || args.length <= 0)
            args = new String[] { context.portMapper().getConsoleURL() };
        _args = args;
        _shellCommand = new ShellCommand();
        _state = INITIALIZED;
    }
            
    /**
     *  Constructor from SysTray
     *
     *  @since 0.9.18
     */
    public UrlLauncher() {
        _state = UNINITIALIZED;
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(UrlLauncher.class);
        _mgr = null;
        _args = null;
        _shellCommand = new ShellCommand();
        _state = INITIALIZED;
    }

    /**
     *  Prevent bad user experience by waiting for the server to be there
     *  before launching the browser.
     *
     *  @return success
     */
    private static boolean waitForServer(String urlString) {
        URI url;
        try {
            url = new URI(urlString);
        } catch (URISyntaxException e) {
            return false;
        }
        String host = url.getHost();
        int port = url.getPort();
        if (port <= 0) {
            port = "https".equals(url.getScheme()) ? 443 : 80;
        }
        SocketAddress sa;
        try {
            sa = new InetSocketAddress(host, port);
        } catch (IllegalArgumentException iae) {
            return false;
        }
        long done = System.currentTimeMillis() + MAX_WAIT_TIME;
        for (int i = 0; i < MAX_TRIES; i++) {
            try {
                Socket test = null;
                try {
                    test = new Socket();
                    // this will usually fail right away if it's going to fail since it's local
                    test.connect(sa, WAIT_TIME);
                    // it worked
                } finally {
                    if (test != null) try { test.close(); } catch (IOException ioe) {}
                }
                // Jetty 6 seems to start the Connector before the
                // webapp is completely ready
                try {
                   Thread.sleep(2*1000);
                } catch (InterruptedException ie) {}
                return true;
            } catch (IOException e) {}
            if (System.currentTimeMillis() > done)
                break;
            try {
                Thread.sleep(WAIT_TIME);
            } catch (InterruptedException ie) {}
        }
        return false;
    }

    /**
     * Discovers the operating system the installer is running under and tries
     * to launch the given URL using the default browser for that platform; if
     * unsuccessful, an attempt is made to launch the URL using the most common
     * browsers.
     * 
     * BLOCKING. This repeatedly probes the server port at the given url
     * until it is apparently ready.
     * 
     * @param  url The URL to open.
     * @return     <code>true</code> if the operation was successful, otherwise
     *             <code>false</code>.
     * 
     * @throws IOException
     */ 
    public boolean openUrl(String url) throws IOException {
        if (_log.shouldDebug()) _log.debug("Waiting for server");
        waitForServer(url);
        if (_log.shouldDebug()) _log.debug("Done waiting for server");
        if (validateUrlFormat(url)) {
            String cbrowser = _context.getProperty(PROP_BROWSER);
            if (cbrowser != null) {
                return openUrl(url, cbrowser);
            }
            if (SystemVersion.isMac()) {
                String osName = System.getProperty("os.name");
                if (osName.toLowerCase(Locale.US).startsWith("mac os x")) {
                    String[] args = new String[] { "open", url };
                    if (_log.shouldDebug()) _log.debug("Execute: " + Arrays.toString(args));
                    if (_shellCommand.executeSilentAndWaitTimed(args , 5))
                        return true;
                } else {
                    return false;
                }
                String[] args = new String[] { "iexplore", url };
                if (_log.shouldDebug()) _log.debug("Execute: " + Arrays.toString(args));
                if (_shellCommand.executeSilentAndWaitTimed(args , 5))
                    return true;
            } else if (SystemVersion.isWindows()) {
                String[] browserString  = new String[] { "C:\\Program Files\\Internet Explorer\\iexplore.exe", "-nohome", url };
                File foo = new File(_context.getTempDir(), "browser" + _context.random().nextLong() + ".reg");
                String[] args = new String[] { "regedit", "/E", foo.getAbsolutePath(), "HKEY_CLASSES_ROOT\\http\\shell\\open\\command" };
                if (_log.shouldDebug()) _log.debug("Execute: " + Arrays.toString(args));
                boolean ok = _shellCommand.executeSilentAndWait(args);
                if (ok) {
                    BufferedReader bufferedReader = null;
                    try {
                        bufferedReader = new BufferedReader(new InputStreamReader(new FileInputStream(foo), "UTF-16"));
                        for (String line; (line = bufferedReader.readLine()) != null; ) {
                            // @="\"C:\\Program Files (x86)\\Mozilla Firefox\\firefox.exe\" -osint -url \"%1\""
                            if (line.startsWith("@=")) {
                                if (_log.shouldDebug()) _log.debug("From RegEdit: " + line);
                                line = line.substring(2).trim();
                                if (line.startsWith("\"") && line.endsWith("\""))
                                    line = line.substring(1, line.length() - 1);
                                line = line.replace("\\\\", "\\");
                                line = line.replace("\\\"", "\"");
                                if (_log.shouldDebug()) _log.debug("Mod RegEdit: " + line);
                                // "C:\Program Files (x86)\Mozilla Firefox\firefox.exe" -osint -url "%1"
                                // use the whole line
                                String[] aarg = parseArgs(line, url);
                                if (aarg.length > 0) {
                                    browserString = aarg;
                                    break;
                                }
                            }
                        }
                    } catch (IOException e) {
                        if (_log.shouldWarn())
                            _log.warn("Reading regedit output", e);
                    } finally {
                        if (bufferedReader != null)
                            try { bufferedReader.close(); } catch (IOException ioe) {}
                        foo.delete();
                    }
                } else if (_log.shouldWarn()) {
                    _log.warn("Regedit Failed: " + Arrays.toString(args));
                }
                if (_log.shouldDebug()) _log.debug("Execute: " + Arrays.toString(browserString));
                if (_shellCommand.executeSilentAndWaitTimed(browserString, 5))
                    return true;
                if (_log.shouldInfo()) _log.info("Failed: " + Arrays.toString(browserString));
            } else {
                // fall through
            }
            String[] args = new String[2];
            args[1] = url;
            for (int i = 0; i < BROWSERS.length; i++) {
                args[0] = BROWSERS[i];
                if (_log.shouldDebug()) _log.debug("Execute: " + Arrays.toString(args));
                if (_shellCommand.executeSilentAndWaitTimed(args, 5))
                    return true;
                if (_log.shouldInfo()) _log.info("Failed: " + Arrays.toString(args));
            }
        }
        return false;
    }

    /**
     * Opens the given URL with the given browser.
     * As of 0.9.38, the browser parameter will be parsed into arguments
     * separated by spaces or tabs.
     * %1, if present, will be replaced with the url.
     * Arguments may be surrounded by single or double quotes if
     * they contain spaces or tabs.
     * There is no mechanism to escape quotes or other chars with backslashes.
     * 
     * BLOCKING. However, this does NOT probe the server port to see if it is ready.
     * 
     * @param  url     The URL to open.
     * @param  browser The browser to use. See above for quoting rules.
     * @return         <code>true</code> if the operation was successful,
     *                 otherwise <code>false</code>.
     * 
     * @throws IOException
     */
    public boolean openUrl(String url, String browser) throws IOException {
        waitForServer(url);
        if (validateUrlFormat(url)) {
            String[] args = parseArgs(browser, url);
            if (args.length > 0) {
                if (_log.shouldDebug()) _log.debug("Execute: " + Arrays.toString(args));
                if (_shellCommand.executeSilentAndWaitTimed(args, 5))
                    return true;
            }
        }
        return false;
    }

    /**
     *  Parse args into arguments
     *  separated by spaces or tabs.
     *  %1, if present, will be replaced with the url,
     *  otherwise it will be added as the last argument.
     *  Arguments may be surrounded by single or double quotes if
     *  they contain spaces or tabs.
     *  There is no mechanism to escape quotes or other chars with backslashes.
     *  Adapted from i2ptunnel SSLHelper.
     *
     *  @return param args non-null
     *  @return non-null
     *  @since 0.9.38
     */
    private static String[] parseArgs(String args, String url) {
        List<String> argList = new ArrayList<String>(4);
        StringBuilder buf = new StringBuilder(32);
        boolean isQuoted = false;
        for (int j = 0; j < args.length(); j++) {
            char c = args.charAt(j);
            switch (c) {
                case '\'':
                case '"':
                    if (isQuoted) {
                        String str = buf.toString().trim();
                        if (str.length() > 0)
                            argList.add(str);
                        buf.setLength(0);
                    }
                    isQuoted = !isQuoted;
                    break;
                case ' ':
                case '\t':
                    // whitespace - if we're in a quoted section, keep this as part of the quote,
                    // otherwise use it as a delim
                    if (isQuoted) {
                        buf.append(c);
                    } else {
                        String str = buf.toString().trim();
                        if (str.length() > 0)
                            argList.add(str);
                        buf.setLength(0);
                    }
                    break;
                default:
                    buf.append(c);
                    break;
            }
        }
        if (buf.length() > 0) {
            String str = buf.toString().trim();
            if (str.length() > 0)
                argList.add(str);
        }
        if (argList.isEmpty())
            return new String[] {};
        boolean foundpct = false;
        // replace %1 with the url
        for (int i = 0; i < argList.size(); i++) {
            String arg = argList.get(i);
            if (arg.contains("%1")) {
                argList.set(i, arg.replace("%1", url));
                foundpct = true;
            }
        }
        // add url if no %1
        if (!foundpct)
            argList.add(url);
        return argList.toArray(new String[argList.size()]);
    }

    private static boolean validateUrlFormat(String urlString) {
         try {
            // just to check validity
            new URI(urlString);
        } catch (URISyntaxException e) {
            return false;
        }
        return true;
    }

    /**
     *  ClientApp interface
     *  @since 0.9.18
     */
    public void startup() {
        String url = _args[0];
        if (!validateUrlFormat(url)) {
            changeState(START_FAILED, new MalformedURLException("Bad url: " + url));
            return;
        }
        changeState(STARTING);
        Thread t = new I2PAppThread(new Runner(), "UrlLauncher", true);
        t.start();
    }

    private class Runner implements Runnable {
        public void run() {
            changeState(RUNNING);
            try {
                String url = _args[0];
                openUrl(url);
                changeState(STOPPED);
            } catch (IOException e) {
                changeState(CRASHED, e);
            }
        }
    }

    /**
     *  ClientApp interface
     *  @since 0.9.18
     */
    public ClientAppState getState() {
        return _state;
    }

    /**
     *  ClientApp interface
     *  @since 0.9.18
     */
    public String getName() {
        return REGISTERED_NAME;
    }

    /**
     *  ClientApp interface
     *  @since 0.9.18
     */
    public String getDisplayName() {
        return REGISTERED_NAME + " \"" + _args[0] + '"';
    }

    /**
     *  @since 0.9.18
     */
    private void changeState(ClientAppState state) {
        changeState(state, null);
    }

    /**
     *  @since 0.9.18
     */
    private synchronized void changeState(ClientAppState state, Exception e) {
        _state = state;
        if (_mgr != null)
            _mgr.notify(this, state, null, e);
    }

    /**
     *  ClientApp interface
     *  @since 0.9.18
     */
    public void shutdown(String[] args) {
        // doesn't really do anything
        changeState(STOPPED);
    }

    /**
     *  Obsolete, now uses ClientApp interface
     */
    public static void main(String args[]) {
        UrlLauncher launcher = new UrlLauncher();
        try {
            if (args.length > 0)
                launcher.openUrl(args[0]);
            else
                launcher.openUrl(I2PAppContext.getGlobalContext().portMapper().getConsoleURL());
         } catch (IOException e) {}
    }
}
