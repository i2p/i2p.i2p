package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */


import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * In case its useful later...
 * (e.g. w/ native programatic thread dumping, etc)
 *
 */
public class I2PThread extends Thread {
    private static Log _log;
    private static Set _listeners = new HashSet(4);

    public I2PThread() {
        super();
    }

    public I2PThread(String name) {
        super(name);
    }

    public I2PThread(Runnable r) {
        super(r);
    }

    public I2PThread(Runnable r, String name) {
        super(r, name);
    }

    public void run() {
        try {
            super.run();
        } catch (Throwable t) {
            try {
                // we cant assume log is created
                if (_log == null) _log = new Log(I2PThread.class);
                _log.log(Log.CRIT, "Killing thread " + getName(), t);
            } catch (Throwable woof) {
                System.err.println("Died within the OOM itself");
                t.printStackTrace();
            }
            if (t instanceof OutOfMemoryError)
                fireOOM((OutOfMemoryError)t);
        }
    }
    
    private void fireOOM(OutOfMemoryError oom) {
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

    public static void main(String args[]) {
        I2PThread t = new I2PThread(new Runnable() {
            public void run() {
                throw new NullPointerException("blah");
            }
        });
        t.start();
        try {
            Thread.sleep(10000);
        } catch (Throwable tt) {
        }
    }
}