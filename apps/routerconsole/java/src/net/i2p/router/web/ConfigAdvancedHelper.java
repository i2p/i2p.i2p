package net.i2p.router.web;

import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.router.RouterContext;

public class ConfigAdvancedHelper {
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
    
    public ConfigAdvancedHelper() {}
    
    public String getSettings() {
        StringBuffer buf = new StringBuffer(4*1024);
        Set names = _context.router().getConfigSettings();
        TreeSet sortedNames = new TreeSet(names);
        for (Iterator iter = sortedNames.iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            String val = _context.router().getConfigSetting(name);
            buf.append(name).append('=').append(val).append('\n');
        }
        return buf.toString();
    }
}
