/*
 * I2P - An anonymous, secure, and fully-distributed communication network.
 * 
 * UrlLauncher.java
 * 2004 The I2P Project
 * This code is public domain.
 */

package net.i2p.apps.systray;

import java.net.MalformedURLException;
import java.net.URL;

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

                if (_shellCommand.executeSilentAndWaitTimed("rundll32 url.dll,FileProtocolHandler " + url, 5))
                    return true;

                if (_shellCommand.executeSilentAndWaitTimed("\"C:\\Program Files\\Internet Explorer\\iexplore.exe\" " + url, 5))
                    return true;
                
            } else {
                
                if (_shellCommand.executeSilentAndWaitTimed("konqueror " + url, 5))
                    return true;
                
                if (_shellCommand.executeSilentAndWaitTimed("galeon " + url, 5))
                    return true;
            }

            if (_shellCommand.executeSilentAndWaitTimed("opera -newpage " + url, 5))
                return true;

            if (_shellCommand.executeSilentAndWaitTimed("firefox " + url, 5))
                return true;
            
            if (_shellCommand.executeSilentAndWaitTimed("mozilla " + url, 5))
                return true;
            
            if (_shellCommand.executeSilentAndWaitTimed("netscape " + url, 5))
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

        if (validateUrlFormat(url))
            if (_shellCommand.executeSilentAndWaitTimed(browser + " " + url, 5))
                return true;

        return false;
    }

    private boolean validateUrlFormat(String urlString) {
        try {
            URL url = new URL(urlString);
        } catch (MalformedURLException e) {
            return false;
        }
        return true;
    }
}
