package net.i2p.syndie;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;
import net.i2p.syndie.web.RemoteArchiveBean;

public class Updater {
    public static final String VERSION = "1.0";
    private static final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(Updater.class);
    private static final Updater _instance = new Updater();
    private long _lastUpdate;
    private static boolean _woken;
    
    public void update() {
        BlogManager bm = BlogManager.instance();
        if (_lastUpdate + bm.getUpdateDelay()*60*60*1000 > System.currentTimeMillis()) {
            if (!_woken)
                return;
        }
        _lastUpdate = System.currentTimeMillis();
        _log.debug("Update started.");
        String[] archives = bm.getUpdateArchives();
        for (int i = 0; i < archives.length; i++) {
            _log.debug("Fetching [" + archives[i] + "]");
            fetchArchive(archives[i]);
            _log.debug("Done fetching " + archives[i]);
        }
        _log.debug("Done fetching archives");
        List rssFeeds = bm.getRssFeeds();
        Iterator iter = rssFeeds.iterator();
        while(iter.hasNext()) {
            String args[] = (String[])iter.next();
            _log.debug("rss feed begin: " + args[0]);
            Sucker sucker = new Sucker(args);
            sucker.suck();
            _log.debug("rss feed end: " + args[0]);
        }
        _log.debug("Done with all updating");
    }
    
    public void fetchArchive(String archive) {
        if ( (archive == null) || (archive.trim().length() <= 0) ) {
            _log.error("Fetch a null archive?" + new Exception("source"));
            return;
        }
        BlogManager bm = BlogManager.instance();
        User user = new User();
        RemoteArchiveBean rab = new RemoteArchiveBean();
        
        rab.fetchIndex(user, "web", archive, bm.getDefaultProxyHost(), bm.getDefaultProxyPort(), true);
        if (rab.getRemoteIndex() != null) {
            HashMap parameters = new HashMap();
            parameters.put("action", new String[] {"Fetch all new entries"});
            rab.fetchSelectedBulk(user, parameters, true);
        } 
        _log.debug(rab.getStatus());
    }

    public static void main() {
        _woken = false;
        _instance.run();
    }
    
    public void run() {

        // wait
        try {
            Thread.currentThread().sleep(5*60*1000);
        } catch (InterruptedException ie) {}

        // creates the default user if necessary
        BlogManager.instance().getDefaultUser();
        while (true) {
            int delay = BlogManager.instance().getUpdateDelay();
            update();
            try {
                synchronized (this) {
                    _woken = false;
                    wait(delay * 60 * 60 * 1000);
                }
            } catch (InterruptedException exp) {
            }

        }
    }
 
    public static void wakeup() {
        synchronized (_instance) {
            _woken = true;
            _instance.notifyAll();
        }
    }
}
