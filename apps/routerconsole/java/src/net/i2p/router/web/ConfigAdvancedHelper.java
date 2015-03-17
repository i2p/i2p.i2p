package net.i2p.router.web;

import java.util.Map;
import java.util.TreeMap;


public class ConfigAdvancedHelper extends HelperBase {
    public ConfigAdvancedHelper() {}
    
    public String getSettings() {
        StringBuilder buf = new StringBuilder(4*1024);
        TreeMap<String, String> sorted = new TreeMap();
        sorted.putAll(_context.router().getConfigMap());
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            String name = e.getKey();
            String val = e.getValue();
            buf.append(name).append('=').append(val).append('\n');
        }
        return buf.toString();
    }
}
