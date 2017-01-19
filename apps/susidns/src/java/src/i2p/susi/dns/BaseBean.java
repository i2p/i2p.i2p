package i2p.susi.dns;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;

/**
 * Holds methods common to several Beans.
 * @since 0.9.1
 */
public class BaseBean
{
    protected final I2PAppContext _context;
    protected final Properties properties;
    protected String action, lastSerial, serial;

    private long configLastLoaded;
    private static final String PRIVATE_BOOK = "private_addressbook";
    private static final String DEFAULT_PRIVATE_BOOK = "../privatehosts.txt";

    public static final String RC_PROP_THEME_NAME = "routerconsole.theme";
    public static final String RC_PROP_UNIVERSAL_THEMING = "routerconsole.universal.theme";
    public static final String PROP_THEME_NAME = "theme";
    public static final String DEFAULT_THEME = "light";
    public static final String BASE_THEME_PATH = "/themes/susidns/";
    public static final String PROP_PW_ENABLE = "routerconsole.auth.enable";
    private static final String ADDRESSBOOK_DIR = "addressbook";
    private static final String CONFIG_FILE = "config.txt";

    public BaseBean()
    {
        _context = I2PAppContext.getGlobalContext();
        properties = new OrderedProperties();
    }

    /**
     * @since 0.9.13 moved from ConfigBean.addressbookPrefix
     */
    protected File addressbookDir() {
        return new File(_context.getRouterDir(), ADDRESSBOOK_DIR);
    }

    /**
     * @since 0.9.13 moved from ConfigBean.configFileName
     */
    protected File configFile() {
        return new File(addressbookDir(), CONFIG_FILE);
    }

    protected void loadConfig()
    {
        synchronized (BaseBean.class) {
            long currentTime = System.currentTimeMillis();
            if( !properties.isEmpty() &&  currentTime - configLastLoaded < 10000 )
                return;
            reload();
        }
    }

    /**
     * @since 0.9.13 moved from ConfigBean
     */
    protected void reload() {
        try {
            synchronized (BaseBean.class) {
                properties.clear();
                // use loadProps to trim
                DataHelper.loadProps(properties, configFile());
                // added in 0.5, for compatibility with 0.4 config.txt
                if( properties.getProperty(PRIVATE_BOOK) == null)
                    properties.setProperty(PRIVATE_BOOK, DEFAULT_PRIVATE_BOOK);
                configLastLoaded = System.currentTimeMillis();
            }
        }
        catch (IOException e) {
            warn(e);
        }
    }

    /**
     * Returns the theme path
     * @since 0.9.1
     */
    public String getTheme() {
        loadConfig();
        String url = BASE_THEME_PATH;
        String theme = properties.getProperty(PROP_THEME_NAME, DEFAULT_THEME);
        boolean universalTheming = _context.getBooleanProperty(RC_PROP_UNIVERSAL_THEMING);
        if (universalTheming) {
            // Fetch routerconsole theme (or use our default if it doesn't exist)
            theme = _context.getProperty(RC_PROP_THEME_NAME, DEFAULT_THEME);
            // Ensure that theme exists
            String[] themes = getThemes();
            boolean themeExists = false;
            for (int i = 0; i < themes.length; i++) {
                if (themes[i].equals(theme))
                    themeExists = true;
            }
            if (!themeExists)
                theme = DEFAULT_THEME;
        }
        url += theme + "/";
        return url;
    }

    /**
     * Get all themes
     * @return String[] -- Array of all the themes found.
     * @since 0.9.2
     */
    public String[] getThemes() {
            String[] themes = null;
            // "docs/themes/susidns/"
            File dir = new File(_context.getBaseDir(), "docs/themes/susidns");
            FileFilter fileFilter = new FileFilter() { public boolean accept(File file) { return file.isDirectory(); } };
            // Walk the themes dir, collecting the theme names, and append them to the map
            File[] dirnames = dir.listFiles(fileFilter);
            if (dirnames != null) {
                themes = new String[dirnames.length];
                for(int i = 0; i < dirnames.length; i++) {
                    themes[i] = dirnames[i].getName();
                }
            }
            // return the map.
            return themes;
    }

    /**
     * @since 0.9.13 moved from subclasses
     */
    public String getAction() {
        return action;
    }

    /**
     * @since 0.9.13 moved from subclasses
     */
    public void setAction(String action) {
        this.action = DataHelper.stripHTML(action);
    }

    /**
     * @since 0.9.13 moved from subclasses
     */
    public String getSerial() {
        lastSerial = Long.toString(_context.random().nextLong());
        action = null;
        return lastSerial;
    }

    /**
     * @since 0.9.13 moved from subclasses
     */
    public void setSerial(String serial) {
        this.serial = DataHelper.stripHTML(serial);
    }

    /**
     * Translate
     * @since 0.9.13 moved from subclasses
     */
    protected static String _t(String s) {
        return Messages.getString(s);
    }

    /**
     * Translate
     * @since 0.9.13 moved from subclasses
     */
    protected static String _t(String s, Object o) {
        return Messages.getString(s, o);
    }

    /**
     * Translate
     * @since 0.9.13 moved from subclasses
     */
    protected static String _t(String s, Object o, Object o2) {
        return Messages.getString(s, o, o2);
    }

    /**
     * Translate (ngettext)
     * @since 0.9.13 moved from subclasses
     */
    protected static String ngettext(String s, String p, int n) {
        return Messages.getString(n, s, p);
    }

    /**
     * @since 0.9.13 moved from Debug
     */
    protected void debug(String msg) {
        Log log = _context.logManager().getLog(getClass());
        if (log.shouldLog(Log.DEBUG))
            log.debug(msg);
    }

    /**
     * @since 0.9.13 moved from Debug
     */
    protected void warn(Throwable t) {
        Log log = _context.logManager().getLog(getClass());
        if (log.shouldLog(Log.WARN))
            log.warn("SusiDNS", t);
    }
}
