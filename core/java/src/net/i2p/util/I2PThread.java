package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

/**
 * In case its useful later...
 * (e.g. w/ native programatic thread dumping, etc)
 *
 */
public class I2PThread extends Thread {
    private static Log _log;
    private static OOMEventListener _lsnr;

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
            if ((t instanceof OutOfMemoryError) && (_lsnr != null)) _lsnr.outOfMemory((OutOfMemoryError) t);
            // we cant assume log is created
            if (_log == null) _log = new Log(I2PThread.class);
            _log.log(Log.CRIT, "Killing thread " + getName(), t);
        }
    }

    public static void setOOMEventListener(OOMEventListener lsnr) {
        _lsnr = lsnr;
    }

    public static OOMEventListener getOOMEventListener() {
        return _lsnr;
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