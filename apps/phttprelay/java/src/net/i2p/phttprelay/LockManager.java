package net.i2p.phttprelay;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.HashSet;
import java.util.Set;

/**
 * Lock identities for updating messages (so that they aren't read / deleted 
 * while being written)
 *
 */
class LockManager {
    private volatile static Set _locks = new HashSet(); // target

    public static void lockIdent(String target) {
        while (true) {
            synchronized (_locks) {
                if (!_locks.contains(target)) {
                    _locks.add(target);
                    return;
                }
                try {
                    _locks.wait(1000);
                } catch (InterruptedException ie) {
                }
            }
        }
    }

    public static void unlockIdent(String target) {
        synchronized (_locks) {
            _locks.remove(target);
            _locks.notifyAll();
        }
    }
}