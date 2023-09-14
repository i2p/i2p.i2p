package net.i2p.router.web.helpers;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.i2p.data.DataHelper;
import net.i2p.router.Router;
import net.i2p.router.web.HelperBase;
import net.i2p.router.web.RouterConsoleRunner;

public class ConfigAdvancedHelper extends HelperBase {
    static final String PROP_FLOODFILL_PARTICIPANT = "router.floodfillParticipant";
    private static final String PROP_AUTH_PFX = RouterConsoleRunner.PROP_CONSOLE_PW + '.';

    private static final Set<String> _hideKeys;
    static {
        // do not show these settings in the UI
        String[] keys = {
            "i2cp.keyPassword", "i2cp.keystorePassword",
            "i2np.ntcp2.sp",
            "netdb.family.keyPassword", "netdb.family.keystorePassword",
            Router.PROP_IB_RANDOM_KEY, Router.PROP_OB_RANDOM_KEY,
            "router.reseedProxy.password", "router.reseedSSLProxy.password",
            RouterConsoleRunner.PROP_KEY_PASSWORD, RouterConsoleRunner.PROP_KEYSTORE_PASSWORD
        };
        _hideKeys = new HashSet<String>(Arrays.asList(keys));
    }

    public String getSettings() {
        StringBuilder buf = new StringBuilder(4*1024);
        TreeMap<String, String> sorted = new TreeMap<String, String>();
        sorted.putAll(_context.router().getConfigMap());
        boolean adv = isAdvanced();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            String key = e.getKey();
            if (!adv &&
                ( _hideKeys.contains(key) ||
                  key.startsWith("i2cp.auth.") ||
                  key.startsWith(PROP_AUTH_PFX))) {
                continue;
            }
            String name = DataHelper.escapeHTML(key);
            String val = DataHelper.escapeHTML(e.getValue());
            buf.append(name).append('=').append(val).append('\n');
        }
        return buf.toString();
    }

    /** @since 0.9.14.1 */
    public String getConfigFileName() {
        return _context.router().getConfigFilename();
    }

    /** @since 0.9.20 */
    public String getFFChecked(int mode) {
        String ff = _context.getProperty(PROP_FLOODFILL_PARTICIPANT, "auto");
        if ((mode == 0 && ff.equals("false")) ||
            (mode == 1 && ff.equals("true")) ||
            (mode == 2 && ff.equals("auto")))
            return CHECKED;
        return "";
    }

    /** @since 0.9.21 */
    public boolean isFloodfill() {
        return _context.netDbSegmentor().floodfillEnabled();
    }
}
