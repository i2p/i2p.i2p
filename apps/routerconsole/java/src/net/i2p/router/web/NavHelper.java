package net.i2p.router.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;
import net.i2p.app.ClientApp;
import net.i2p.app.ClientAppManager;
import net.i2p.app.ClientAppState;
import net.i2p.app.NavService;

public class NavHelper implements NavService, ClientApp {
    // both indexed by standard (untranslated) app name
    private final Map<String, App> _apps = new ConcurrentHashMap<String, App>(4);
    private final Map<String, byte[]> _binary = new ConcurrentHashMap<String, byte[]>(4);
    
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
     * @since 0.9.20 added iconpath parameter
     */
    public void registerApp(String appName, String displayName, String path, String tooltip, String iconpath) {
        if (iconpath != null && !iconpath.startsWith("/"))
            iconpath = null;
        _apps.put(appName, new App(displayName, tooltip, path, iconpath));
    }

    /**
     * @param name standard name for the app
     */
    public void unregisterApp(String name) {
        _apps.remove(name);
    }
    
    /**
     *  Retrieve binary icon for a plugin
     *  @param name plugin name
     *  @return null if not found
     *  @since 0.9.25
     */
    public byte[] getBinary(String name){
        if(name != null)
            return _binary.get(name);
        else
            return null;
    }

    /**
     *  Store binary icon for a plugin
     *  @param name plugin name
     *  @since 0.9.25
     */
    public void setBinary(String name, byte[] arr){
        _binary.put(name, arr);
    }


    /**
     *  Translated string is loaded by PluginStarter
     *  @return map of translated name to HTML string, or null if none
     */
    public Map<String, String> getClientAppLinks() {
        if (_apps.isEmpty())
            return null;
        Map<String, String> rv = new HashMap<String, String>(_apps.size());
        StringBuilder buf = new StringBuilder(128);
        for (Map.Entry<String, App> e : _apps.entrySet()) {
            String appName = e.getKey();
            App app = e.getValue();
            String path = app.url;
            if (path == null)
                continue;
            String name = app.name;
            buf.setLength(0);
            buf.append("<tr><td>");
            getClientAppImg(buf, appName, app.icon);
            buf.append("</td><td align=\"left\"><a target=\"_blank\" href=\"").append(path).append("\" ");
            String tip = app.desc;
            if (tip != null)
                buf.append("title=\"").append(tip).append("\" ");
            buf.append('>').append(name.replace(" ", "&nbsp;")).append("</a></td></tr>\n");
            rv.put(name, buf.toString());
        }
        return rv;
    }
    
    /**
     *  Get 16x16 icon img and append to buf
     *  @param name standard app name
     *  @since 0.9.45
     */
    private void getClientAppImg(StringBuilder buf, String name, String iconpath) {
            if (iconpath != null) {
                buf.append("<img src=\"").append(iconpath).append("\" height=\"16\" width=\"16\" alt=\"\">");
            } else if (name.equals("orchid")) {
                buf.append("<img src=\"/themes/console/light/images/flower.png\" alt=\"\">");
            } else if (name.equals("i2pbote")) {
                buf.append("<img src=\"/themes/console/light/images/mail_black.png\" alt=\"\">");
            } else {
                buf.append("<img src=\"/themes/console/images/plugin.png\" height=\"16\" width=\"16\" alt=\"\">");
            }
    }

    /**
     *  For HomeHelper. 32x32 icon paths.
     *  @param ctx unused
     *  @return non-null, possibly empty, unsorted
     *  @since 0.9, public since 0.9.33, was package private
     */
    public List<App> getClientApps(I2PAppContext ctx) {
        if (_apps.isEmpty())
            return Collections.emptyList();
        List<App> rv = new ArrayList<App>(_apps.size());
        for (Map.Entry<String, App> e : _apps.entrySet()) {
            String name = e.getKey();
            App mapp = e.getValue();
            if (mapp.url == null)
                continue;
            String tip = mapp.desc;
            if (tip == null)
                tip = "";
            String icon = mapp.icon;
            if (icon == null) {
                // hardcoded hack
                if (name.equals("i2pbote"))
                    icon = "/themes/console/images/email.png";
                else
                    icon = "/themes/console/images/plugin.png";
            }
            App app = new App(mapp.name, tip, mapp.url, icon);
            rv.add(app);
        }
        return rv;
    }

    /** @since 0.9.56 */
    public static NavHelper getInstance() {
        return getInstance(I2PAppContext.getGlobalContext());
    }

    /** @since 0.9.56 */
    public static NavHelper getInstance(I2PAppContext ctx) {
        ClientAppManager cmgr = ctx.clientAppManager();
        if (cmgr != null)
            return (NavHelper) cmgr.getRegisteredApp("NavHelper");
        return null;
    }

    /////// ClientApp methods

    /** @since 0.9.56 */
    public void startup() {}

    /** @since 0.9.56 */
    public void shutdown(String[] args) {
        _apps.clear();
        _binary.clear();
    }

    /** @since 0.9.56 */
    public ClientAppState getState() {
        return ClientAppState.RUNNING;
    }

    /** @since 0.9.56 */
    public String getName() {
        return "NavHelper";
    }

    /** @since 0.9.56 */
    public String getDisplayName() {
        return "Nav Helper";
    }

    /////// end ClientApp methods
}
