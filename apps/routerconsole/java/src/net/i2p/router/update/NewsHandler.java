package net.i2p.router.update;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import net.i2p.router.RouterContext;
import net.i2p.router.web.ConfigUpdateHelper;
import net.i2p.update.*;
import static net.i2p.update.UpdateType.*;
import static net.i2p.update.UpdateMethod.*;

/**
 * Task to periodically look for updates to the news.xml, and to keep
 * track of whether that has an announcement for a new version.
 *
 * Overrides UpdateRunner for convenience, this is not an Updater
 *
 * @since 0.9.4 moved from NewsFetcher
 */
class NewsHandler extends UpdateHandler implements Checker {
    
    /**
     *  NOTE: If you change, also change in Android:
     *  app/src/main/java/net/i2p/android/apps/NewsFetcher.java
     *
     *  @since 0.7.14, configurable since 0.9.51
     */
    // psi.i2p
    //private static final String BACKUP_NEWS_URL = "http://avviiexdngd32ccoy4kuckvc3mkf53ycvzbz6vz75vzhv4tbpk5a.b32.i2p/news.xml";
    //private static final String BACKUP_NEWS_URL_SU3 = "http://avviiexdngd32ccoy4kuckvc3mkf53ycvzbz6vz75vzhv4tbpk5a.b32.i2p/news.su3";
    // str4d
    //private static final String BACKUP_NEWS_URL = "http://ivk5a6wfjar6hjucjmnbcea5inwmwg5b3hsv72x77xwyhbeaajja.b32.i2p/news/news.xml";
    //private static final String BACKUP_NEWS_URL_SU3 = "http://ivk5a6wfjar6hjucjmnbcea5inwmwg5b3hsv72x77xwyhbeaajja.b32.i2p/news/news.su3";
    // idk
    private static final String BACKUP_NEWS_URL = "http://dn3tvalnjz432qkqsvpfdqrwpqkw3ye4n4i2uyfr4jexvo3sp5ka.b32.i2p/news/news.atom.xml";
    private static final String DEFAULT_BACKUP_NEWS_URL_SU3 = "http://dn3tvalnjz432qkqsvpfdqrwpqkw3ye4n4i2uyfr4jexvo3sp5ka.b32.i2p/news/news.su3";
    private static final String PROP_BACKUP_NEWS_URL_SU3 = "router.backupNewsURL";

    public NewsHandler(RouterContext ctx, ConsoleUpdateManager mgr) {
        super(ctx, mgr);
    }

    /**
     *  This will check for news or router updates (it does the same thing).
     *  Should not block.
     *  @param currentVersion ignored, stored locally
     */
    public UpdateTask check(UpdateType type, UpdateMethod method,
                            String id, String currentVersion, long maxTime) {
        if ((type != ROUTER_SIGNED && type != NEWS && type != NEWS_SU3) ||
            method != HTTP)
            return null;
        List<URI> updateSources = new ArrayList<URI>(2);
        try {
            // This may be su3 or xml
            updateSources.add(new URI(ConfigUpdateHelper.getNewsURL(_context)));
        } catch (URISyntaxException use) {}
        try {
            //updateSources.add(new URI(BACKUP_NEWS_URL));
            updateSources.add(new URI(_context.getProperty(PROP_BACKUP_NEWS_URL_SU3, DEFAULT_BACKUP_NEWS_URL_SU3)));
        } catch (URISyntaxException use) {}
        UpdateRunner update = new NewsFetcher(_context, _mgr, updateSources);
        return update;
    }
}
