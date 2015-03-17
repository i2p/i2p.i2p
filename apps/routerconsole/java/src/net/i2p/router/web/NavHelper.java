package net.i2p.router.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;

public class NavHelper {
    private static Map<String, String> _apps = new ConcurrentHashMap(4);
    private static Map<String, String> _tooltips = new ConcurrentHashMap(4);
    
    /**
     * To register a new client application so that it shows up on the router
     * console's nav bar, it should be registered with this singleton. 
     *
     * @param name pretty name the app will be called in the link
     * @param path full path pointing to the application's root 
     *             (e.g. /i2ptunnel/index.jsp)
     */
    public static void registerApp(String name, String path) {
        _apps.put(name, path);
    }

    public static void registerApp(String name, String path, String tooltip) {
        _apps.put(name, path);
        _tooltips.put(name, tooltip);
    }

    public static void unregisterApp(String name) {
        _apps.remove(name);
        _tooltips.remove(name);
    }
    
    /**
     *  Translated string is loaded by PluginStarter
     *  @param ctx unused
     */
    public static String getClientAppLinks(I2PAppContext ctx) {
        if (_apps.isEmpty())
            return "";
        StringBuilder buf = new StringBuilder(256); 
        List<String> l = new ArrayList(_apps.keySet());
        Collections.sort(l);
        for (String name : l) {
            String path = _apps.get(name);
            if (path == null)
                continue;
            buf.append(" <a target=\"_blank\" href=\"").append(path).append("\" ");
            String tip = _tooltips.get(name);
            if (tip != null)
                buf.append("title=\"").append(tip).append("\" ");
            buf.append('>').append(name).append("</a>");
        }
        return buf.toString();
    }
    
    /**
     *  For HomeHelper
     *  @param ctx unused
     *  @return non-null, possibly empty
     *  @since 0.9
     */
    static List<HomeHelper.App> getClientApps(I2PAppContext ctx) {
        if (_apps.isEmpty())
            return Collections.EMPTY_LIST;
        List<HomeHelper.App> rv = new ArrayList(_apps.size());
        for (Map.Entry<String, String> e : _apps.entrySet()) {
            String name = e.getKey();
            String path = e.getValue();
            if (path == null)
                continue;
            String tip = _tooltips.get(name);
            if (tip == null)
                tip = "";
            // hardcoded hack
            String icon;
            if (path.equals("/i2pbote/index.jsp"))
                icon = "/themes/console/images/email.png";
            else
                icon = "/themes/console/images/plugin.png";
            HomeHelper.App app = new HomeHelper.App(name, tip, path, icon);
            rv.add(app);
        }
        return rv;
    }
}
