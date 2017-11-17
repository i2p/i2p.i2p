/* I2PSOCKSTunnel is released under the terms of the GNU GPL,
 * with an additional exception.  For further details, see the
 * licensing terms in I2PTunnel.java.
 *
 * Copyright (c) 2004 by human
 */
package net.i2p.socks;

/**
 * @since 0.9.33 Moved out of net.i2p.i2ptunnel.socks.SOCKS4aServer
 */
public class SOCKS4Constants {

    private SOCKS4Constants() {}

    public static final int SOCKS_VERSION_4 = 0x04;

    /*
     * Some namespaces to enclose SOCKS protocol codes
     */
    public static class Command {
        public static final int CONNECT = 0x01;
        public static final int BIND = 0x02;
    }

    public static class Reply {
        public static final int SUCCEEDED = 0x5a;
        public static final int CONNECTION_REFUSED = 0x5b;
    }
}
