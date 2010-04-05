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
}
