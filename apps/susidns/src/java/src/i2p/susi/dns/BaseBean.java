package i2p.susi.dns;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

import net.i2p.I2PAppContext;

/**
 * Holds methods common to several Beans.
 * @since 0.9.1
 */
public class BaseBean
{
    private final I2PAppContext _context;
    protected final Properties properties;

    private long configLastLoaded = 0;
    private static final String PRIVATE_BOOK = "private_addressbook";
    private static final String DEFAULT_PRIVATE_BOOK = "../privatehosts.txt";

    public static final String RC_PROP_THEME_NAME = "routerconsole.theme";
    public static final String RC_PROP_UNIVERSAL_THEMING = "routerconsole.universal.theme";
    public static final String PROP_THEME_NAME = "theme";
    public static final String DEFAULT_THEME = "light";
    public static final String BASE_THEME_PATH = "/themes/susidns/";

    public BaseBean()
    {
        _context = I2PAppContext.getGlobalContext();
        properties = new Properties();
    }

    protected void loadConfig()
    {
        long currentTime = System.currentTimeMillis();

        if( !properties.isEmpty() &&  currentTime - configLastLoaded < 10000 )
            return;

        FileInputStream fis = null;
        try {
            properties.clear();
            fis = new FileInputStream( ConfigBean.configFileName );
            properties.load( fis );
            // added in 0.5, for compatibility with 0.4 config.txt
            if( properties.getProperty(PRIVATE_BOOK) == null)
                properties.setProperty(PRIVATE_BOOK, DEFAULT_PRIVATE_BOOK);
            configLastLoaded = currentTime;
        }
        catch (Exception e) {
            Debug.debug( e.getClass().getName() + ": " + e.getMessage() );
        } finally {
            if (fis != null)
                try { fis.close(); } catch (IOException ioe) {}
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
}
