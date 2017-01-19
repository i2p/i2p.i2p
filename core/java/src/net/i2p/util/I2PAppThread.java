package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */


import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Like {@link I2PThread} but with per-thread OOM listeners,
 * rather than a static router-wide listener list,
 * so that an OOM in an app won't call the router listener
 * to shutdown the whole router.
 *
 * This is preferred for application use.
 * See {@link I2PThread} for features.
 */
public class I2PAppThread extends I2PThread {

    private final Set<OOMEventListener> _threadListeners = new CopyOnWriteArraySet<OOMEventListener>();

    public I2PAppThread() {
        super();
    }

    public I2PAppThread(String name) {
        super(name);
    }

    public I2PAppThread(Runnable r) {
        super(r);
    }

    public I2PAppThread(Runnable r, String name) {
        super(r, name);
    }

    public I2PAppThread(Runnable r, String name, boolean isDaemon) {
        super(r, name, isDaemon);
    }

    /**
     *  @since 0.9.23
     */
    public I2PAppThread(ThreadGroup group, Runnable r, String name) {
        super(group, r, name);
    }
    
    @Override
    protected void fireOOM(OutOfMemoryError oom) {
        for (OOMEventListener listener : _threadListeners)
            listener.outOfMemory(oom);
    }

    /** register a new component that wants notification of OOM events */
    public void addOOMEventThreadListener(OOMEventListener lsnr) {
        _threadListeners.add(lsnr);
    }

    /** unregister a component that wants notification of OOM events */    
    public void removeOOMEventThreadListener(OOMEventListener lsnr) {
        _threadListeners.remove(lsnr);
    }
}
