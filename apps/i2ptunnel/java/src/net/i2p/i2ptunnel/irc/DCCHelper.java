package net.i2p.i2ptunnel.irc;

/**
 *  Hooks to create and maintain DCC client and server tunnels
 *
 *  @since 0.8.9
 */
public interface DCCHelper {

    public boolean isEnabled();

    /**
     *  String to put in the outgoing DCC
     */
    public String getB32Hostname();

    /**
     *  An outgoing DCC request
     *
     *  @param ip local irc client IP
     *  @param port local irc client port
     *  @param type string
     *  @return i2p port or -1 on error
     */
    public int newOutgoing(byte[] ip, int port, String type);

    /**
     *  An incoming DCC request
     *
     *  @param b32 remote dcc server address
     *  @param port remote dcc server port
     *  @param type string
     *  @return local server port or -1 on error
     */
    public int newIncoming(String b32, int port, String type);

}
