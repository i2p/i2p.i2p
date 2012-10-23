package net.i2p.update;

/**
 *  Transport mechanism for getting something.
 *
 *  @since 0.9.4
 */
public enum UpdateMethod {
    METHOD_DUMMY,      // Internal use only
    HTTP,              // .i2p or via outproxy
    HTTP_CLEARNET,     // direct non-.i2p
    HTTPS_CLEARNET,    // direct non-.i2p
    TORRENT,
    GNUTELLA,
    IMULE,
    TAHOE_LAFS,
    DEBIAN,
    FILE               // local file
}
