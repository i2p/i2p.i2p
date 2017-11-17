/* I2PSOCKSTunnel is released under the terms of the GNU GPL,
 * with an additional exception.  For further details, see the
 * licensing terms in I2PTunnel.java.
 *
 * Copyright (c) 2004 by human
 */
package net.i2p.socks;

/**
 * @since 0.9.33 Moved out of net.i2p.i2ptunnel.socks.SOCKS5Server
 */
public class SOCKS5Constants {

    private SOCKS5Constants() {}

    public static final int SOCKS_VERSION_5 = 0x05;

    /*
     * Some namespaces to enclose SOCKS protocol codes
     */
    public static class Method {
        public static final int NO_AUTH_REQUIRED = 0x00;
        public static final int USERNAME_PASSWORD = 0x02;
        public static final int NO_ACCEPTABLE_METHODS = 0xff;
    }

    public static class AddressType {
        public static final int IPV4 = 0x01;
        public static final int DOMAINNAME = 0x03;
        public static final int IPV6 = 0x04;
    }

    public static class Command {
        public static final int CONNECT = 0x01;
        public static final int BIND = 0x02;
        public static final int UDP_ASSOCIATE = 0x03;
    }

    public static class Reply {
        public static final int SUCCEEDED = 0x00;
        public static final int GENERAL_SOCKS_SERVER_FAILURE = 0x01;
        public static final int CONNECTION_NOT_ALLOWED_BY_RULESET = 0x02;
        public static final int NETWORK_UNREACHABLE = 0x03;
        public static final int HOST_UNREACHABLE = 0x04;
        public static final int CONNECTION_REFUSED = 0x05;
        public static final int TTL_EXPIRED = 0x06;
        public static final int COMMAND_NOT_SUPPORTED = 0x07;
        public static final int ADDRESS_TYPE_NOT_SUPPORTED = 0x08;
    }

    public static final int AUTH_VERSION = 1;
    public static final int AUTH_SUCCESS = 0;
    public static final int AUTH_FAILURE = 1;
}
