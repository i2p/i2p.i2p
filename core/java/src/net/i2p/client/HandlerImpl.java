package net.i2p.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * Base class for handling I2CP messages
 *
 * @author jrandom
 */
abstract class HandlerImpl implements I2CPMessageHandler {
    protected final Log _log;
    private final int _type;
    protected final I2PAppContext _context;

    public HandlerImpl(I2PAppContext context, int type) {
        _context = context;
        _type = type;
        _log = context.logManager().getLog(getClass());
    }
    
    public int getType() {
        return _type;
    }
}
