package net.i2p.router;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import net.i2p.crypto.PersistentSessionKeyManager;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.util.Log;

/**
 * Centralize the sessionKeyManager persistence (rather than leave it to a private
 * job in the startup job)
 *
 */
public class SessionKeyPersistenceHelper implements Service {
    private Log _log;
    private RouterContext _context;
    private SessionKeyWriterJob _writerJob;
    private final static long PERSIST_DELAY = 3*60*1000;
    private final static String PROP_SESSION_KEY_FILE = "router.sessionKeys.location";
    private final static String DEFAULT_SESSION_KEY_FILE = "sessionKeys.dat";
    
    public SessionKeyPersistenceHelper(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(SessionKeyPersistenceHelper.class);
        _writerJob = new SessionKeyWriterJob();
    }
    
    public void shutdown() {
        writeState();
    }
    
    public void restart() {
        writeState();
        startup();
    }
    
    private String getKeyFile() {
        String val = _context.router().getConfigSetting(PROP_SESSION_KEY_FILE);
        if (val == null)
            val = DEFAULT_SESSION_KEY_FILE;
        return val;
    }
            
    public void startup() {
        SessionKeyManager mgr = _context.sessionKeyManager();
        if (mgr instanceof PersistentSessionKeyManager) {
            PersistentSessionKeyManager manager = (PersistentSessionKeyManager)mgr;
            File f = new File(getKeyFile());
            if (f.exists()) {
                FileInputStream fin = null;
                try {
                    fin = new FileInputStream(f);
                    manager.loadState(fin);
                    int expired = manager.aggressiveExpire();
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Session keys loaded [not error] with " + expired 
                                   + " sets immediately expired");
                } catch (Throwable t) {
                    _log.error("Error reading in session key data", t);
                } finally {
                    if (fin != null) try { fin.close(); } catch (IOException ioe) {}
                }
            }
            _context.jobQueue().addJob(_writerJob);
        }
    }
    
    private void writeState() {
        Object o = _context.sessionKeyManager();
        if (!(o instanceof PersistentSessionKeyManager)) {
            _log.error("Unable to persist the session key state - manager is " + o.getClass().getName());
            return;
        }
        PersistentSessionKeyManager mgr = (PersistentSessionKeyManager)o;

        // only need for synchronization is during shutdown()
        synchronized (mgr) {
            FileOutputStream fos = null;
            try {
                int expired = mgr.aggressiveExpire();
                if (expired > 0) {
                    _log.info("Agressive expired " + expired + " tag sets");
                }
                fos = new FileOutputStream(getKeyFile());
                mgr.saveState(fos);
                fos.flush();
                _log.debug("Session keys written");
            } catch (Throwable t) {
                _log.debug("Error writing session key state", t);
            } finally { 
                if (fos != null) try { fos.close(); } catch (IOException ioe) {}
            }
        }
    }
    
    public void renderStatusHTML(OutputStream out) { }
    
    private class SessionKeyWriterJob extends JobImpl {
        public SessionKeyWriterJob() {
            super(SessionKeyPersistenceHelper.this._context);
            getTiming().setStartAfter(PERSIST_DELAY);
        }
        public String getName() { return "Write Session Keys"; }
        public void runJob() { 
            writeState();
            requeue(PERSIST_DELAY);
        }
    }
}
