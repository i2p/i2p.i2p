package net.i2p.update;

/**
 *  What to update
 *
 *  @since 0.9.4
 */
public enum UpdateType {
    TYPE_DUMMY,
    NEWS,
    ROUTER_SIGNED,
    ROUTER_SIGNED_PACK200,      // unused, use ROUTER_SIGNED for both
    ROUTER_UNSIGNED,
    PLUGIN, PLUGIN_INSTALL,
    GEOIP, BLOCKLIST, RESEED,
    HOMEPAGE,
    ADDRESSBOOK
}
