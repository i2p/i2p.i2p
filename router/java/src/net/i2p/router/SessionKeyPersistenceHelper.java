package net.i2p.router;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

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
    private final static long PERSIST_DELAY = 3*60*1000;
    private final static String SESSION_KEY_FILE = "sessionKeys.dat";
    
    public SessionKeyPersistenceHelper(RouterContext context) {
        _context = context;
        _log = _context.logManager().getLog(SessionKeyPersistenceHelper.class);
    }
    
    public void shutdown() {
        writeState();
    }
    
    public void startup() {
        SessionKeyManager mgr = _context.sessionKeyManager();
        if (mgr instanceof PersistentSessionKeyManager) {
            PersistentSessionKeyManager manager = (PersistentSessionKeyManager)mgr;
            File f = new File(SESSION_KEY_FILE);
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
            _context.jobQueue().addJob(new SessionKeyWriterJob());
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
                fos = new FileOutputStream(SESSION_KEY_FILE);
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
    
    public String renderStatusHTML() { return ""; }
    
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
