package net.i2p.syndie;

import java.util.HashMap;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;
import net.i2p.syndie.web.RemoteArchiveBean;

public class Updater {
    public static final String VERSION = "1.0";
    private static final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(Updater.class);
    private static final Updater _instance = new Updater();
    private long _lastUpdate;
    
    public void update() {
        BlogManager bm = BlogManager.instance();
        if (_lastUpdate + bm.getUpdateDelay()*60*60*1000 > System.currentTimeMillis()) {
            return;
        }
        _lastUpdate = System.currentTimeMillis();
        _log.debug("Update started.");
        User user = new User();
        String[] archives = bm.getUpdateArchives();
        for (int i = 0; i < archives.length; i++) {
            RemoteArchiveBean rab = new RemoteArchiveBean();
            _log.debug("Fetching " + archives[i]);
            rab.fetchIndex(user, "web", archives[i], bm.getDefaultProxyHost(), bm.getDefaultProxyPort());
            if (rab.getRemoteIndex() != null) {
                _log.debug("Index fetched, getting new entries.");
                HashMap parameters = new HashMap();
                parameters.put("action", new String[] {"Fetch all new entries"});
                rab.fetchSelectedBulk(user, parameters);
                _log.debug(rab.getStatus());
                _log.debug("Update finished.");
            } else {
                _log.debug("Index fetch failed.");
            }
        }
    }

    public static void main() {
        _instance.run();
    }
    
    public void run() {

        // wait
        try {
            Thread.currentThread().sleep(5*60*1000);
        } catch (InterruptedException ie) {}
        
        while (true) {
            int delay = BlogManager.instance().getUpdateDelay();
            if (delay < 1) delay = 1;
            update();
            try {
                synchronized (this) {
                    wait(delay * 60 * 60 * 1000);
                }
            } catch (InterruptedException exp) {
            }

        }
    }
 
    public static void wakeup() {
        synchronized (_instance) {
            _instance.notifyAll();
        }
    }
}