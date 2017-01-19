package net.i2p.update;

/**
 *  What to update
 *
 *  @since 0.9.4
 */
public enum UpdateType {
    /** Dummy: internal use only */
    TYPE_DUMMY,
    NEWS,
    ROUTER_SIGNED,
    ROUTER_UNSIGNED,
    PLUGIN,
    /** unused */
    GEOIP,
    BLOCKLIST,
    /** unused */
    RESEED,
    /** unused */
    HOMEPAGE,
    /** unused */
    ADDRESSBOOK,
    /** @since 0.9.9 */
    ROUTER_SIGNED_SU3,
    /** @since 0.9.15 */
    NEWS_SU3,
    /** @since 0.9.17 */
    ROUTER_DEV_SU3
}
