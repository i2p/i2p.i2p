package net.i2p.router.web;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.i2p.router.RouterContext;

public class NavHelper {
    private static Map _apps = new HashMap();
    private RouterContext _context;
    /**
     * Configure this bean to query a particular router context
     *
     * @param contextId begging few characters of the routerHash, or null to pick
     *                  the first one we come across.
     */
    public void setContextId(String contextId) {
        try {
            _context = ContextHelper.getContext(contextId);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
    
    public NavHelper() {}
    
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
    public static void unregisterApp(String name) {
        _apps.remove(name);
    }
    
    public String getClientAppLinks() {
        StringBuffer buf = new StringBuffer(1024); 
        for (Iterator iter = _apps.keySet().iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            String path = (String)_apps.get(name);
            buf.append("<a href=\"").append(path).append("\">");
            buf.append(name).append("</a> |");
        }
        return buf.toString();
    }
}
