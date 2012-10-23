package net.i2p.update;

/**
 *  What to update
 *
 *  @since 0.9.4
 */
public enum UpdateType {
    TYPE_DUMMY,   // Internal use only
    NEWS,
    ROUTER_SIGNED,
    ROUTER_UNSIGNED,
    PLUGIN,
    GEOIP,
    BLOCKLIST,
    RESEED,
    HOMEPAGE,
    ADDRESSBOOK
}
