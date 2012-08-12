package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */


import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * In case its useful later...
 * (e.g. w/ native programatic thread dumping, etc)
 *
 */
public class I2PThread extends Thread {
    /**
     *  Non-static to avoid refs to old context in Android.
     *  Probably should just remove all the logging though.
     *  Logging removed, too much trouble with extra contexts
     */
    //private volatile Log _log;
    private static final Set _listeners = new CopyOnWriteArraySet();
    //private String _name;
    //private Exception _createdBy;

    public I2PThread() {
        super();
        //if ( (_log == null) || (_log.shouldLog(Log.DEBUG)) )
        //    _createdBy = new Exception("Created by");
    }

    public I2PThread(String name) {
        super(name);
        //if ( (_log == null) || (_log.shouldLog(Log.DEBUG)) )
        //    _createdBy = new Exception("Created by");
    }

    public I2PThread(Runnable r) {
        super(r);
        //if ( (_log == null) || (_log.shouldLog(Log.DEBUG)) )
        //    _createdBy = new Exception("Created by");
    }

    public I2PThread(Runnable r, String name) {
        super(r, name);
        //if ( (_log == null) || (_log.shouldLog(Log.DEBUG)) )
        //    _createdBy = new Exception("Created by");
    }
    public I2PThread(Runnable r, String name, boolean isDaemon) {
        super(r, name);
	setDaemon(isDaemon);
        //if ( (_log == null) || (_log.shouldLog(Log.DEBUG)) )
        //    _createdBy = new Exception("Created by");
    }
    
    public I2PThread(ThreadGroup g, Runnable r) {
        super(g, r);
        //if ( (_log == null) || (_log.shouldLog(Log.DEBUG)) )
        //    _createdBy = new Exception("Created by");
    }

/****
    private void log(int level, String msg) { log(level, msg, null); }

    private void log(int level, String msg, Throwable t) {
        // we cant assume log is created
        if (_log == null) _log = new Log(I2PThread.class);
        if (_log.shouldLog(level))
            _log.log(level, msg, t);
    }
****/
    
    @Override
    public void run() {
        //_name = Thread.currentThread().getName();
        //log(Log.INFO, "New thread started" + (isDaemon() ? " (daemon): " : ": ") + _name, _createdBy);
        try {
            super.run();
        } catch (Throwable t) {
          /****
            try {
                log(Log.CRIT, "Thread terminated unexpectedly: " + getName(), t);
            } catch (Throwable woof) {
                System.err.println("Died within the OOM itself");
                t.printStackTrace();
            }
          ****/
            if (t instanceof OutOfMemoryError)
                fireOOM((OutOfMemoryError)t);
        }
        // This creates a new I2PAppContext after it was deleted
        // in Router.finalShutdown() via RouterContext.killGlobalContext()
        //log(Log.INFO, "Thread finished normally: " + _name);
    }
    
/****
    @Override
    protected void finalize() throws Throwable {
        //log(Log.DEBUG, "Thread finalized: " + _name);
        super.finalize();
    }
****/
    
    protected void fireOOM(OutOfMemoryError oom) {
        for (Iterator iter = _listeners.iterator(); iter.hasNext(); ) {
            OOMEventListener listener = (OOMEventListener)iter.next();
            listener.outOfMemory(oom);
        }
    }

    /** register a new component that wants notification of OOM events */
    public static void addOOMEventListener(OOMEventListener lsnr) {
        _listeners.add(lsnr);
    }

    /** unregister a component that wants notification of OOM events */    
    public static void removeOOMEventListener(OOMEventListener lsnr) {
        _listeners.remove(lsnr);
    }

    public interface OOMEventListener {
        public void outOfMemory(OutOfMemoryError err);
    }

/****
    public static void main(String args[]) {
        I2PThread t = new I2PThread(new Runnable() {
            public void run() {
                throw new NullPointerException("blah");
            }
        });
        t.start();
        try {
            Thread.sleep(10000);
        } catch (Throwable tt) { // nop
        }
    }
****/
}
