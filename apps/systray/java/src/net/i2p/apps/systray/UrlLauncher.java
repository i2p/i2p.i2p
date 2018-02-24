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
import java.util.Locale;

import net.i2p.I2PAppContext;
import net.i2p.app.*;
import static net.i2p.app.ClientAppState.*;
import net.i2p.util.I2PAppThread;
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
     *  @since 0.9.18
     */
    public UrlLauncher(I2PAppContext context, ClientAppManager mgr, String[] args) {
        _state = UNINITIALIZED;
        _context = context;
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
     * BLOCKING
     * 
     * @param  url The URL to open.
     * @return     <code>true</code> if the operation was successful, otherwise
     *             <code>false</code>.
     * 
     * @throws IOException
     */ 
    public boolean openUrl(String url) throws IOException {
        waitForServer(url);
        if (validateUrlFormat(url)) {
            String cbrowser = _context.getProperty(PROP_BROWSER);
            if (cbrowser != null) {
                return openUrl(url, cbrowser);
            }
            if (SystemVersion.isMac()) {
                String osName = System.getProperty("os.name");
                if (osName.toLowerCase(Locale.US).startsWith("mac os x")) {

                    if (_shellCommand.executeSilentAndWaitTimed("open " + url, 5))
                        return true;

                } else {
                    return false;
                }

                if (_shellCommand.executeSilentAndWaitTimed("iexplore " + url, 5))
                    return true;
            } else if (SystemVersion.isWindows()) {
                String         browserString  = "\"C:\\Program Files\\Internet Explorer\\iexplore.exe\" -nohome";
                BufferedReader bufferedReader = null;

                File foo = new File(_context.getTempDir(), "browser.reg");
                _shellCommand.executeSilentAndWait("regedit /E \"" + foo.getAbsolutePath() + "\" \"HKEY_CLASSES_ROOT\\http\\shell\\open\\command\"");

                try {
                    bufferedReader = new BufferedReader(new InputStreamReader(new FileInputStream(foo), "UTF-16"));
                    for (String line; (line = bufferedReader.readLine()) != null; ) {
                        if (line.startsWith("@=")) {
                            // we should really use the whole line and replace %1 with the url
                            browserString = line.substring(3, line.toLowerCase(Locale.US).indexOf(".exe") + 4);
                            if (browserString.startsWith("\\\""))
                                browserString = browserString.substring(2);
                            browserString = "\"" + browserString + "\"";
                        }
                    }
                    try {
                        bufferedReader.close();
                    } catch (IOException e) {
                        // No worries.
                    }
                    foo.delete();
                } catch (IOException e) {
                    // Defaults to IE.
                } finally {
                    if (bufferedReader != null)
                        try { bufferedReader.close(); } catch (IOException ioe) {}
                }
                if (_shellCommand.executeSilentAndWaitTimed(browserString + ' ' + url, 5))
                    return true;
            } else {
                // fall through
            }
            for (int i = 0; i < BROWSERS.length; i++) {
                if (_shellCommand.executeSilentAndWaitTimed(BROWSERS[i] + ' ' + url, 5))
                    return true;
            }
        }
        return false;
    }

    /**
     * Opens the given URL with the given browser.
     * 
     * BLOCKING
     * 
     * @param  url     The URL to open.
     * @param  browser The browser to use.
     * @return         <code>true</code> if the operation was successful,
     *                 otherwise <code>false</code>.
     * 
     * @throws IOException
     */
    public boolean openUrl(String url, String browser) throws IOException {
        waitForServer(url);
        if (validateUrlFormat(url)) {
            if (_shellCommand.executeSilentAndWaitTimed(browser + " " + url, 5))
                return true;
        }
        return false;
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
