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
import java.net.URL;

import net.i2p.I2PAppContext;
import net.i2p.util.ShellCommand;

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
public class UrlLauncher {

    ShellCommand _shellCommand = new ShellCommand();

    private static final int WAIT_TIME = 5*1000;
    private static final int MAX_WAIT_TIME = 5*60*1000;
    private static final int MAX_TRIES = 99;

    /**
     *  Prevent bad user experience by waiting for the server to be there
     *  before launching the browser.
     *  @return success
     */
    public boolean waitForServer(String urlString) {
        URL url;
        try {
            url = new URL(urlString);
        } catch (MalformedURLException e) {
            return false;
        }
        String host = url.getHost();
        int port = url.getPort();
        if (port <= 0) {
            port = url.getDefaultPort();
            if (port <= 0)
                return false;
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
                Socket test = new Socket();
                // this will usually fail right away if it's going to fail since it's local
                test.connect(sa, WAIT_TIME);
                // it worked
                try {
                   test.close();
                } catch (IOException ioe) {}
                return true;
            } catch (Exception e) {}
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
     * @param  url The URL to open.
     * @return     <code>true</code> if the operation was successful, otherwise
     *             <code>false</code>.
     * 
     * @throws Exception
     */ 
    public boolean openUrl(String url) throws Exception {

        String osName = System.getProperty("os.name");

        waitForServer(url);
        if (validateUrlFormat(url)) {
            if (osName.toLowerCase().indexOf("mac") > -1) {
                if (osName.toLowerCase().startsWith("mac os x")) {

                    if (_shellCommand.executeSilentAndWaitTimed("safari " + url, 5))
                        return true;

                } else {
                    return false;
                }

                if (_shellCommand.executeSilentAndWaitTimed("iexplore " + url, 5))
                    return true;

            } else if (osName.startsWith("Windows")) {

                String         browserString  = "\"C:\\Program Files\\Internet Explorer\\iexplore.exe\" -nohome";
                BufferedReader bufferedReader = null;

                File foo = new File(I2PAppContext.getGlobalContext().getTempDir(), "browser.reg");
                _shellCommand.executeSilentAndWait("regedit /E \"" + foo.getAbsolutePath() + "\" \"HKEY_CLASSES_ROOT\\http\\shell\\open\\command\"");

                try {
                    bufferedReader = new BufferedReader(new InputStreamReader(new FileInputStream(foo), "UTF-16"));
                    for (String line; (line = bufferedReader.readLine()) != null; ) {
                        if (line.startsWith("@=")) {
                            // we should really use the whole line and replace %1 with the url
                            browserString = line.substring(3, line.toLowerCase().indexOf(".exe") + 4);
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
                } catch (Exception e) {
                    // Defaults to IE.
                } finally {
                    if (bufferedReader != null)
                        try { bufferedReader.close(); } catch (IOException ioe) {}
                }
                if (_shellCommand.executeSilentAndWaitTimed(browserString + " " + url, 5))
                    return true;

            } else {

                // fall through
            }

            // This debian script tries everything in $BROWSER, then gnome-www-browser and x-www-browser
            // if X is running and www-browser otherwise. Those point to the user's preferred
            // browser using the update-alternatives system.
            if (_shellCommand.executeSilentAndWaitTimed("sensible-browser " + url, 5))
                return true;

            // Try x-www-browser directly
            if (_shellCommand.executeSilentAndWaitTimed("x-www-browser " + url, 5))
                return true;

            // puppy linux
            if (_shellCommand.executeSilentAndWaitTimed("defaultbrowser " + url, 5))
                return true;

            if (_shellCommand.executeSilentAndWaitTimed("opera -newpage " + url, 5))
                return true;

            if (_shellCommand.executeSilentAndWaitTimed("firefox " + url, 5))
                return true;

            if (_shellCommand.executeSilentAndWaitTimed("mozilla " + url, 5))
                return true;

            if (_shellCommand.executeSilentAndWaitTimed("netscape " + url, 5))
                return true;

            if (_shellCommand.executeSilentAndWaitTimed("konqueror " + url, 5))
                return true;

            if (_shellCommand.executeSilentAndWaitTimed("galeon " + url, 5))
                return true;
            
            // Text Mode Browsers only below here
            if (_shellCommand.executeSilentAndWaitTimed("www-browser " + url, 5))
                return true;

            if (_shellCommand.executeSilentAndWaitTimed("links " + url, 5))
                return true;

            if (_shellCommand.executeSilentAndWaitTimed("lynx " + url, 5))
                return true;
            
        }
        return false;
    }

    /**
     * Opens the given URL with the given browser.
     * 
     * @param  url     The URL to open.
     * @param  browser The browser to use.
     * @return         <code>true</code> if the operation was successful,
     *                 otherwise <code>false</code>.
     * 
     * @throws Exception
     */
    public boolean openUrl(String url, String browser) throws Exception {

        waitForServer(url);
        if (validateUrlFormat(url))
            if (_shellCommand.executeSilentAndWaitTimed(browser + " " + url, 5))
                return true;

        return false;
    }

    private boolean validateUrlFormat(String urlString) {
        try {
            // just to check validity
            new URL(urlString);
        } catch (MalformedURLException e) {
            return false;
        }
        return true;
    }

    public static void main(String args[]) {
        UrlLauncher launcher = new UrlLauncher();
        try {
            if (args.length > 0)
                launcher.openUrl(args[0]);
            else
                launcher.openUrl("http://127.0.0.1:7657/index.jsp");
         } catch (Exception e) {}
    }
}
