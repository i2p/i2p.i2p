package net.i2p.router.web;

import java.util.Map;
import java.util.TreeMap;

import net.i2p.data.DataHelper;

public class ConfigAdvancedHelper extends HelperBase {
    public ConfigAdvancedHelper() {}
    
    public String getSettings() {
        StringBuilder buf = new StringBuilder(4*1024);
        TreeMap<String, String> sorted = new TreeMap<String, String>();
        sorted.putAll(_context.router().getConfigMap());
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            String name = DataHelper.escapeHTML(e.getKey());
            String val = DataHelper.escapeHTML(e.getValue());
            buf.append(name).append('=').append(val).append('\n');
        }
        return buf.toString();
    }

    /** @since 0.9.14.1 */
    public String getConfigFileName() {
        return _context.router().getConfigFilename();
    }
}
