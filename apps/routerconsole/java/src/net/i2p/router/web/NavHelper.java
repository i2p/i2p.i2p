package net.i2p.router.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;
import net.i2p.router.web.App;

public class NavHelper {
    private static final Map<String, String> _apps = new ConcurrentHashMap<String, String>(4);
    private static final Map<String, String> _tooltips = new ConcurrentHashMap<String, String>(4);
    private static final Map<String, String> _icons = new ConcurrentHashMap<String, String>(4);
    private static final Map<String, byte[]> _binary = new ConcurrentHashMap<String, byte[]>(4);
    
    /**
     * To register a new client application so that it shows up on the router
     * console's nav bar, it should be registered with this singleton. 
     *
     * @param name pretty name the app will be called in the link
     * @param path full path pointing to the application's root 
     *             (e.g. /i2ptunnel/index.jsp), non-null
     * @param tooltip HTML escaped text or null
     * @param iconpath path-only URL starting with /, HTML escaped, or null
     * @since 0.9.20 added iconpath parameter
     */
    public static void registerApp(String name, String path, String tooltip, String iconpath) {
        _apps.put(name, path);
        if (tooltip != null)
            _tooltips.put(name, tooltip);
        if (iconpath != null && iconpath.startsWith("/"))
            _icons.put(name, iconpath);

    }

    public static void unregisterApp(String name) {
        _apps.remove(name);
        _tooltips.remove(name);
        _icons.remove(name);
    }
    
    /**
     *  Retrieve binary icon for a plugin
     *  @param name plugin name
     *  @return null if not found
     *  @since 0.9.25
     */
    public static byte[] getBinary(String name){
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
    public static void setBinary(String name, byte[] arr){
        _binary.put(name, arr);
    }


    /**
     *  Translated string is loaded by PluginStarter
     *  @param ctx unused
     */
    public static String getClientAppLinks(I2PAppContext ctx) {
        if (_apps.isEmpty())
            return "";
        StringBuilder buf = new StringBuilder(256); 
        List<String> l = new ArrayList<String>(_apps.keySet());
        Collections.sort(l);
        for (String name : l) {
            String path = _apps.get(name);
            if (path == null)
                continue;
            buf.append(" <a target=\"_blank\" href=\"").append(path).append("\" ");
            String tip = _tooltips.get(name);
            if (tip != null)
                buf.append("title=\"").append(tip).append("\" ");
            buf.append('>').append(name.replace(" ", "&nbsp;")).append("</a>");
        }
        return buf.toString();
    }
    
    /**
     *  For HomeHelper
     *  @param ctx unused
     *  @return non-null, possibly empty
     *  @since 0.9, public since 0.9.33, was package private
     */
    public static List<App> getClientApps(I2PAppContext ctx) {
        if (_apps.isEmpty())
            return Collections.emptyList();
        List<App> rv = new ArrayList<App>(_apps.size());
        for (Map.Entry<String, String> e : _apps.entrySet()) {
            String name = e.getKey();
            String path = e.getValue();
            if (path == null)
                continue;
            String tip = _tooltips.get(name);
            if (tip == null)
                tip = "";
            String icon;
            if (_icons.containsKey(name))
                icon = _icons.get(name);
            // hardcoded hack
            else if (path.equals("/i2pbote/index.jsp"))
                icon = "/themes/console/images/email.png";
            else
                icon = "/themes/console/images/plugin.png";
            App app = new App(name, tip, path, icon);
            rv.add(app);
        }
        return rv;
    }
}
