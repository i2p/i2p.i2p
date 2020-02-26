package org.rrd4j;

/**
 * Enumeration of available datasource types.
 */
public enum DsType {
    /**
     * Is for things like temperatures or number of people in a room or the value of a RedHat share.
     */
    GAUGE,

    /**
     * Is for continuous incrementing counters like the ifInOctets counter in a router.
     */
    COUNTER,

    /**
     * Will store the derivative of the line going from the last to the current value of the data source.
     */
    DERIVE,

    /**
     * Is for counters which get reset upon reading.
     */
    ABSOLUTE
}
