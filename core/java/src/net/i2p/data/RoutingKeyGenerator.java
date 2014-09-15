package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.I2PAppContext;

/**
 * Component to manage the munging of hashes into routing keys - given a hash, 
 * perform some consistent transformation against it and return the result.
 * This transformation is fed by the current "mod data".  
 *
 * As of 0.9.16, this is essentially just an interface.
 * Implementation moved to net.i2p.data.router.RouterKeyGenerator.
 * No generator is available in I2PAppContext; you must be in RouterContext.
 *
 */
public abstract class RoutingKeyGenerator {

    /**
     * Get the generator for this context.
     *
     * @return null in I2PAppContext; non-null in RouterContext.
     */
    public static RoutingKeyGenerator getInstance() {
        return I2PAppContext.getGlobalContext().routingKeyGenerator();
    }

    /**
     *  The version of the current (today's) mod data.
     *  Use to determine if the routing key should be regenerated.
     */
    public abstract long getLastChanged();

    /**
     * Get the routing key for a key.
     *
     * @throws IllegalArgumentException if origKey is null
     */
    public abstract Hash getRoutingKey(Hash origKey);

}
