package net.i2p.router.web;

import java.io.File;

/**
 *  If news file does not exist, use file from the initialNews directory
 *  in $I2P
 *
 *  @since 0.8.2
 */
public class NewsHelper extends ContentHelper {
    
    @Override
    public String getContent() {
        File news = new File(_page);
        if (!news.exists())
            _page = (new File(_context.getBaseDir(), "docs/initialNews/initialNews.xml")).getAbsolutePath();
        return super.getContent();
    }

    /** @since 0.8.12 */
    public boolean shouldShowNews() {
        return NewsFetcher.getInstance(_context).shouldShowNews();
    }
}
