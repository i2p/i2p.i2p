package net.i2p.router.web;

import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import net.i2p.router.RouterContext;

public class ConfigAdvancedHelper extends HelperBase {
    public ConfigAdvancedHelper() {}
    
    public String getSettings() {
        StringBuilder buf = new StringBuilder(4*1024);
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
