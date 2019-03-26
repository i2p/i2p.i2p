package net.i2p.client.streaming;

import net.i2p.data.Destination;

/**
 * Something that filters incoming streaming connections.
 * @since 0.9.40
 */
public interface IncomingConnectionFilter {

    /**
     * @param d the destination that wants to establish an
     * incoming connection
     * @return true if the connection should be allowed.
     */
    public boolean allowDestination(Destination d);

    /**
     * Utility implementation that allows all incoming connections
     */
    public static final IncomingConnectionFilter ALLOW = 
        new IncomingConnectionFilter() {
            public boolean allowDestination(Destination d) {
                return true;
            }
        };

    /**
     * Utility implementation that denies all incoming connections
     */
    public static final IncomingConnectionFilter DENY = 
        new IncomingConnectionFilter() {
            public boolean allowDestination(Destination d) {
                return false;
            }
        };
}
