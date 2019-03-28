package net.i2p.i2ptunnel.access;

/**
 * Definition of a Threshold.  
 *
 * A Threshold is defined by a number of connections over a period of minutes
 *
 * @since 0.9.40
 */
class Threshold {

    /** A Threshold that is never breached */
    static final Threshold ALLOW = new Threshold(Integer.MAX_VALUE, 1);
    /** A Threshold that is always breached */
    static final Threshold DENY = new Threshold(0, 1);

    private final int connections;
    private final int minutes;

    Threshold(int connections, int minutes) {
        if (minutes < 1)
            throw new IllegalArgumentException("Threshold must be defined over at least 1 minute");
        if (connections < 0)
            throw new IllegalArgumentException("Accesses cannot be negative");
        this.connections = connections;
        this.minutes = minutes;
    }

    int getConnections() {
        return connections;
    }
    
    int getMinutes() {
        return minutes;
    }
}
