package net.i2p.router.news;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.io.Reader;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.app.ClientApp;
import net.i2p.app.ClientAppManager;
import net.i2p.app.ClientAppState;
import static net.i2p.app.ClientAppState.*;
import net.i2p.data.DataHelper;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;
import net.i2p.util.SystemVersion;
import net.i2p.util.Translate;
import net.i2p.util.TranslateReader;

import org.cybergarage.xml.Node;

/**
 *  Manage current news.
 *  Keeps current entries in memory, and provide methods to
 *  add new entries and store them to disk.
 *
 *  @since 0.9.23
 */
public class NewsManager implements ClientApp {

    private final I2PAppContext _context;
    private final Log _log;
    private final ClientAppManager _cmgr;
    private volatile ClientAppState _state = UNINITIALIZED;
    private List<NewsEntry> _currentNews;
    // TODO
    // Metadata is persisted in the old news.xml format by
    // NewsFetcher.outputOldNewsXML() and read in at startup by
    // ConsoleUpdateManager.startup() and NewsFetcher.checkForUpdates().
    // While running, the UpdateManager keeps the metadata.
    // NewsHelper looks at the news.xml timestamp.
    //private NewsMetadata _currentMetadata;

    public static final String APP_NAME = "news";
    private static final String BUNDLE_NAME = "net.i2p.router.news.messages";
    private static final String WELCOME_AUTHOR = "I2P Development Team";

    /**
     *  @param args ignored
     */
    public NewsManager(I2PAppContext ctx, ClientAppManager listener, String[] args) {
        _context = ctx;
        _cmgr = listener;
        _log = ctx.logManager().getLog(NewsManager.class);
        _state = INITIALIZED;
    }

    /**
     *
     *  @return non-null, sorted by updated date, newest first
     */
    public synchronized List<NewsEntry> getEntries() {
        if (!_currentNews.isEmpty())
            return new ArrayList<NewsEntry>(_currentNews);
        // load old news.xml
        if (_log.shouldWarn())
            _log.warn("no real XML, falling back to news.xml");
        List<NewsEntry> rv = parseOldNews();
        if (!rv.isEmpty()) {
            _currentNews = rv;
            // don't save to disk as we don't have the UUIDs so they will be dups ??
            return rv;
        }
        // load and translate initialnews
        // We don't save it to _currentNews, as the language may change
        if (_log.shouldWarn())
            _log.warn("no news.xml, falling back to initialNews");
        return parseInitialNews();
    }

    /**
     *  Store each entry.
     *  Old entries are always overwritten, as they may change even without the updated date changing.
     *  Does NOT update the NewsEntry list.
     *
     *  @param entries each one should be "entry" at the root
     *  @return success
     */
    public synchronized boolean storeEntries(List<Node> entries) {
        return PersistNews.store(_context, entries);
    }

    /**
     *  Add or replace each entry in the list.
     *  Does NOT store them to disk.
     */
    public synchronized void addEntries(List<NewsEntry> entries) {
        for (NewsEntry e : entries) {
            String id = e.id;
            if (id == null)
                continue;
            String title = e.title;
            boolean found = false;
            for (int i = 0; i < _currentNews.size(); i++) {
                NewsEntry old = _currentNews.get(i);
                // try to prevent dups with those created from old news.xml,
                // where the UUID is the title
                if (id.equals(old.id) || (title != null && title.equals(old.id))) {
                    _currentNews.set(i, e);
                    found = true;
                    break;
                }
            }
            if (!found)
                _currentNews.add(e);
        }
        Collections.sort(_currentNews);
    }

    /////// ClientApp methods

    /**
     *  ClientApp interface
     */
    public synchronized void startup() {
        changeState(STARTING);
        _currentNews = PersistNews.load(_context);
        if (_log.shouldWarn())
            _log.warn("Initialized with " + _currentNews.size() + " entries");
        changeState(RUNNING);
        if (_cmgr != null)
            _cmgr.register(this);
    }

    /**
     *  ClientApp interface
     *  @param args ignored
     */
    public synchronized void shutdown(String[] args) {
        changeState(STOPPED);
    }

    public ClientAppState getState() {
        return _state;
    }

    public String getName() {
        return APP_NAME;
    }

    public String getDisplayName() {
        return "News Manager";
    }

    /////// end ClientApp methods

    private synchronized void changeState(ClientAppState state) {
        _state = state;
        if (_cmgr != null)
            _cmgr.notify(this, state, null, null);
    }

    private List<NewsEntry> parseOldNews() {
        File file = new File(_context.getConfigDir(), "docs/news.xml");
        String newsContent = FileUtil.readTextFile(file.toString(), -1, true);
        if (newsContent == null || newsContent.equals(""))
            return Collections.emptyList();
        return parseNews(newsContent, false);
    }

    /**
     *  The initial (welcome to i2p) news
     *
     *  @return entry with first-installed date stamp, or null
     *  @since 0.9.28
     */
    public NewsEntry getInitialNews() {
        List<NewsEntry> list = parseInitialNews();
        if (list.isEmpty())
            return null;
        NewsEntry rv = list.get(0);
        long installed = _context.getProperty("router.firstInstalled", 0L);
        if (installed > 0)
            rv.updated = installed;
        return rv;
    }

    private List<NewsEntry> parseInitialNews() {
        InputStream is = NewsManager.class.getResourceAsStream("/net/i2p/router/news/resources/docs/initialNews/initialNews.xml");
        if (is == null) {
            if (_log.shouldWarn())
                _log.warn("failed to load initial news");
            return Collections.emptyList();
        }
        Reader reader = null;
        try {
            char[] buf = new char[512];
            StringBuilder out = new StringBuilder(2048);
            reader = new TranslateReader(_context, BUNDLE_NAME, is);
            int len;
            while((len = reader.read(buf)) > 0) {
                out.append(buf, 0, len);
            }
            List<NewsEntry> rv = parseNews(out.toString(), true);
            if (!rv.isEmpty()) {
                //rv.get(0).updated = RFC3339Date.parse3339Date("2015-01-01");
                rv.get(0).updated = _context.clock().now();
                // Tagged in initialNews.xml inside a comment
                rv.get(0).authorName = Translate.getString(WELCOME_AUTHOR, _context, BUNDLE_NAME);
            } else {
                if (_log.shouldWarn())
                    _log.warn("failed to load initial news");
            }
            return rv;
        } catch (IOException ioe) {
            if (_log.shouldWarn())
                _log.warn("failed to load initial news", ioe);
            return Collections.emptyList();
        } finally {
            try {
                is.close();
            } catch (IOException foo) {}
            try {
                if (reader != null)
                    reader.close();
            } catch (IOException foo) {}
        }
    }

    /**
     *  Used for initialNews.xml and news.xml
     *
     *  @param addMissingDiv true for initialNews, false for news.xml
     */
    private List<NewsEntry> parseNews(String newsContent, boolean addMissingDiv) {
        List<NewsEntry> rv = new ArrayList<NewsEntry>();
        // Parse news content for headings.
        boolean foundEntry = false;
        int start = newsContent.indexOf("<h3>");
        while (start >= 0) {
            NewsEntry entry = new NewsEntry();
            // Add offset to start:
            // 4 - gets rid of <h3>
            // 16 - gets rid of the date as well (assuming form "<h3>yyyy-mm-dd: Foobarbaz...")
            // Don't truncate the "congratulations" in initial news
            if (newsContent.length() > start + 16 &&
                newsContent.substring(start + 4, start + 6).equals("20") &&
                newsContent.substring(start + 14, start + 16).equals(": ")) {
                // initialNews.xml, or old news.xml from server
                entry.updated = RFC3339Date.parse3339Date(newsContent.substring(start + 4, start + 14));
                newsContent = newsContent.substring(start+16);
            } else {
                newsContent = newsContent.substring(start+4);
                int colon = newsContent.indexOf(": ");
                if (colon > 0 && colon <= 10) {
                    //  Parse the format we wrote it out in, in NewsFetcher.outputOldNewsXML()
                    //  Doesn't work if the date has a : in it, but SHORT and MEDIUM hopefully do not
                    //  Was originally SHORT, switched to MEDIUM in 0.9.43
                    DateFormat fmt = DateFormat.getDateInstance(DateFormat.MEDIUM);
                    // the router sets the JVM time zone to UTC but saves the original here so we can get it
                    fmt.setTimeZone(SystemVersion.getSystemTimeZone(_context));
                    try {
                        Date date = fmt.parse(newsContent.substring(0, colon));
                        entry.updated = date.getTime();
                    } catch (ParseException pe) {
                        // try SHORT
                        try {
                            fmt = DateFormat.getDateInstance(DateFormat.SHORT);
                            fmt.setTimeZone(SystemVersion.getSystemTimeZone(_context));
                            Date date = fmt.parse(newsContent.substring(0, colon));
                            entry.updated = date.getTime();
                        } catch (ParseException pe2) {
                            // can't find date, will be zero
                        }
                    }
                    newsContent = newsContent.substring(colon + 2);
                }
            }
            int end = newsContent.indexOf("</h3>");
            if (end >= 0) {
                String heading = newsContent.substring(0, end);
                entry.title = heading;
                // use title as UUID
                entry.id = heading;
                newsContent = newsContent.substring(end + 5);
                end = newsContent.indexOf("<h3>");
                if (end > 0)
                    entry.content = newsContent.substring(0, end);
                else
                    entry.content = newsContent;
                // initialNews.xml has the <div> before the <h3>, not after, so we lose it...
                // add it back.
                if (addMissingDiv)
                    entry.content = "<div>\n" + entry.content;
                rv.add(entry);
                start = end;
            }
        }
        Collections.sort(rv);
        return rv;
    }

/****
    public static void main(String[] args) {
        if (args.length != 0) {
            System.err.println("Usage: NewsManager");
            System.exit(1);
        }
        I2PAppContext ctx = new I2PAppContext();
        NewsManager mgr = new NewsManager(ctx, null, null);
        mgr.startup();
        List<NewsEntry> entries = mgr.getEntries();
        System.out.println("Loaded " + entries.size() + " news entries");
        for (int i = 0; i < entries.size(); i++) {
            NewsEntry e = entries.get(i);
            System.out.println("\n****** News #" + (i+1) + ": " + e.title + ' ' + new Date(e.updated) +
                               "\nLink: " + e.link + '\n' + e.content);
        }
    }
****/
}
