package i2p.susi.dns;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Holds methods common to several Beans.
 * @since 0.9.1
 */
public class BaseBean
{
    protected final Properties properties;

    private long configLastLoaded = 0;
    private static final String PRIVATE_BOOK = "private_addressbook";
    private static final String DEFAULT_PRIVATE_BOOK = "../privatehosts.txt";

    public static final String PROP_THEME_NAME = "theme";
    public static final String DEFAULT_THEME = "light";
    public static final String BASE_THEME_PATH = "/themes/susidns/";

    public BaseBean()
    {
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
        url += theme + "/";
        return url;
    }
}
