package net.i2p.router.peermanager;


/**
 * Provide a means of quantifying a profiles fitness in some particular aspect, as well
 * as to coordinate via statics the four known aspects.
 *
 */
public class Calculator {
    /**
     * Evaluate the profile according to the current metric
     */
    public double calc(PeerProfile profile) { return 0.0d; }
    /**
     * Evaluate the profile according to the current metric
     */
    public boolean calcBoolean(PeerProfile profile) { return false; }
}
