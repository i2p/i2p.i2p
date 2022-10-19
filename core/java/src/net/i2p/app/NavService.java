package net.i2p.app;

/**
 *  Service to put links on the console.
 *  Here so webapps can use it without dependency on console.
 *
 *  @since 0.9.56 adapted from console NavHelper
 */
public interface NavService {
    
    /**
     * To register a new client application so that it shows up on the router
     * console's nav bar, it should be registered with this singleton. 
     *
     * @param appName standard name for the app (plugin)
     * @param displayName translated name the app will be called in the link
     *             warning, this is the display name aka ConsoleLinkName, not the plugin name
     * @param path full path pointing to the application's root 
     *             (e.g. /i2ptunnel/index.jsp), non-null
     * @param tooltip HTML escaped text or null
     * @param iconpath path-only URL starting with /, HTML escaped, or null
     */
    public void registerApp(String appName, String displayName, String path, String tooltip, String iconpath);

    /**
     * @param name standard name for the app
     */
    public void unregisterApp(String name);
}
