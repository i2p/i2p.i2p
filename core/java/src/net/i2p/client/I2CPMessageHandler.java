package net.i2p.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.i2cp.I2CPMessage;

/**
 * Define a way to handle a particular type of message
 *
 * @author jrandom
 */
interface I2CPMessageHandler {
    public int getType();

    public void handleMessage(I2CPMessage message, I2PSessionImpl session);
}