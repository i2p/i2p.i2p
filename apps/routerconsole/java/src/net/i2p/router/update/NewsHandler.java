package net.i2p.router.update;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import net.i2p.router.RouterContext;
import net.i2p.router.web.ConfigUpdateHelper;
import net.i2p.update.*;

/**
 * Task to periodically look for updates to the news.xml, and to keep
 * track of whether that has an announcement for a new version.
 *
 * @since 0.9.4 moved from NewsFetcher
 */
class NewsHandler extends UpdateHandler implements Checker {
    
    /** @since 0.7.14 not configurable */
    private static final String BACKUP_NEWS_URL = "http://www.i2p2.i2p/_static/news/news.xml";

    public NewsHandler(RouterContext ctx) {
        super(ctx);
    }

    /**
     *  This will check for news or router updates (it does the same thing).
     *  Should not block.
     *  @param currentVersion ignored, stored locally
     */
    public UpdateTask check(UpdateType type, UpdateMethod method,
                            String id, String currentVersion, long maxTime) {
        if ((type != UpdateType.ROUTER_SIGNED && type != UpdateType.ROUTER_SIGNED_PACK200 && type != UpdateType.NEWS) ||
            method != UpdateMethod.HTTP)
            return null;
        List<URI> updateSources = new ArrayList(2);
        try {
            updateSources.add(new URI(ConfigUpdateHelper.getNewsURL(_context)));
        } catch (URISyntaxException use) {}
        try {
            updateSources.add(new URI(BACKUP_NEWS_URL));
        } catch (URISyntaxException use) {}
        UpdateRunner update = new NewsFetcher(_context, updateSources);
        update.start();
        return update;
    }
}
