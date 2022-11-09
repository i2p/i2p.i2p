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
import java.util.Scanner;

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
    private static final boolean IS_SERVICE = SystemVersion.isService();

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
     * Obtains the default browser for the Windows platform, which by now should
     * be Edgium in the worst-case scenario but in case it isn't, we can use this
     * function to figure it out. It can find:
     *
     * 1. The current user's HTTPS default browser if they configured it to be
     * non-default
     * 2. The current user's HTTP default browser if they configured it to be
     * non-default
     * 3. Edgium if it's available
     * 4. iexplore if it's not
     *
     * and it will return the first one we find in exactly that order.
     *
     * Adapted from:
     * https://stackoverflow.com/questions/15852885/me...
     *
     * @param url containing full scheme, i.e. http://127.0.0.1:7657
     * @return path to command[0] and target URL[1] to the default browser ready for execution, non-null
     * @since 2.0.0
     */
    private String getDefaultWindowsBrowser(String url) {
        String defaultBrowser;
        String key;
        if (url.startsWith("https://")){
            // User-Configured HTTPS Browser
            key = "HKEY_CURRENT_USER\\SOFTWARE\\Microsoft\\Windows\\Shell\\Associations\\URLAssociations\\https\\UserChoice";
        } else {
            // User-Configure HTTP Browser
            key = "HKEY_CURRENT_USER\\SOFTWARE\\Microsoft\\Windows\\Shell\\Associations\\URLAssociations\\http\\UserChoice";
        }
        defaultBrowser = getDefaultOutOfRegistry(key);
        if (defaultBrowser != null)
            return defaultBrowser;
        // MSEdge on pretty much everything after Windows 7
        key = "HKEY_CLASSES_ROOT\\MSEdgeHTM\\shell\\open\\command";
        defaultBrowser = getDefaultOutOfRegistry(key);
        if (defaultBrowser != null)
            return defaultBrowser;
        // iexplore usually, depends on the Windows, sometimes Edge
        key = "HKEY_CLASSES_ROOT\\http\\shell\\open\\command";
        defaultBrowser = getDefaultOutOfRegistry(key);
        if (defaultBrowser != null)
            return defaultBrowser;
        return "C:\\Program Files\\Internet Explorer\\iexplore.exe";
    }

    /**
     * obtains a value matching a key contained in the windows registry at a path
     * represented by hkeyquery
     *
     * @param hkeyquery registry entry to ask for.
     * @param key key to retrieve value from
     * @param additionalArgs additional arguments to pass to the `REG QUERY` command
     * @return either a registry "Default" value or null if one does not exist/is empty
     * @since 2.0.0
     */
    private String registryQuery(String hkeyquery, String key) {
        try {
            // Get registry where we find the default browser
            String[] cmd = {"REG", "QUERY", hkeyquery};
            Process process = Runtime.getRuntime().exec(cmd);
            Scanner kb = new Scanner(process.getInputStream());
            while (kb.hasNextLine()) {
                String line = kb.nextLine().trim();
                if (line.startsWith(key)) {
                    String[] splitLine = line.split("  ");
                    kb.close();
                    String finalValue = splitLine[splitLine.length - 1].trim();
                    if (!finalValue.equals("")) {
                        return finalValue;
                    }
                }
            }
            // Match wasn't found, still need to close Scanner
            kb.close();
        } catch (Exception e) {
            if (_log.shouldError())
                _log.error(hkeyquery, e);
        }
        return null;
    }

    /**
     * If following a query back to the Default value doesn't work then what
     * we have is a "ProgID" which will be registered in \HKEY_CLASSES_ROOT\%ProgId%,
     * and will have an entry \shell\open\command, where \shell\open\command yields the
     * value that contains the command it needs. This function takes a registry query
     * in the same format as getDefaultOutOfRegistry but instead of looking for the
     * default entry
     *
     * @param hkeyquery
     * @return the command required to run the application referenced in hkeyquery, or null
     * @since 2.0.0
     */
    private String followUserConfiguredBrowserToCommand(String hkeyquery) {
        String progIdValue = registryQuery(hkeyquery,"ProgId");
        return followProgIdToCommand(progIdValue);
    }

    /**
     * Cross-references a progId obtained by followUserConfiguredBrowserToCommand against
     * HKEY_CLASSES_ROOT\%ProgId%\shell\open\command, which holds the value of the command
     * which we need to run to launch the default browser.
     *
     * @param hkeyquery
     * @return the command required to run the application referenced in hkeyquery, or null
     * @since 2.0.0
     */
    private String followProgIdToCommand(String progid) {
        String hkeyquery = "HKEY_CLASSES_ROOT\\"+progid+"\\shell\\open\\command";
        String finalValue = registryQuery(hkeyquery, "(Default)");
        if (finalValue != null) {
            if (!finalValue.equals(""))
                return finalValue;
        }
        return null;
    }

    /**
     * obtains a default browsing command out of the Windows registry.
     *
     * @param hkeyquery registry entry to ask for.
     * @return either a registry "Default" value or null if one does not exist/is empty
     * @since 2.0.0
     */
    private String getDefaultOutOfRegistry(String hkeyquery) {
        String defaultValue = registryQuery(hkeyquery, "Default");
        if (defaultValue != null) {
            if (!defaultValue.equals(""))
                return defaultValue;
        }else{
            defaultValue = followUserConfiguredBrowserToCommand(hkeyquery);
            if (defaultValue != null) {
                if (!defaultValue.equals(""))
                    return defaultValue;
            }
        }
        return null;
    }

    /**
     * Discovers the operating system the installer is running under and tries
     * to launch the given URL using the default browser for that platform; if
     * unsuccessful, an attempt is made to launch the URL using the most common
     * browsers.
     * 
     * As of 0.9.46, fails immediately if JVM is a Windows or Linux Service.
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
        if (IS_SERVICE)
            return false;
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
                String line = getDefaultWindowsBrowser(url);
                String[] aarg = parseArgs(line, url);
                if (aarg.length > 0) {
                    browserString = aarg;
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
     * As of 0.9.46, fails immediately if JVM is a Windows or Linux Service.
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
        if (IS_SERVICE)
            return false;
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
     *  As of 0.9.46, stops immediately if JVM is a Windows or Linux Service.
     * 
     *  @since 0.9.18
     */
    public void startup() {
        if (IS_SERVICE) {
            // not START_FAILED so manager doesn't log CRIT
            changeState(STOPPED);
            return;
        }
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
