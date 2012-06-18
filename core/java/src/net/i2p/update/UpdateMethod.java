package net.i2p.update;

/**
 *  Transport mechanism for getting something.
 *
 *  @since 0.9.2
 */
public enum UpdateMethod {
    METHOD_DUMMY,
    HTTP,              // .i2p or via outproxy
    HTTP_CLEARNET,     // direct non-.i2p
    TORRENT,
    GNUTELLA, IMULE, TAHOE_LAFS,
    DEBIAN
}
