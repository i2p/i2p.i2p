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
     *  @return local DCC server i2p port or -1 on error
     */
    public int newOutgoing(byte[] ip, int port, String type);

    /**
     *  An incoming DCC request
     *
     *  @param b32 remote dcc server b32 address
     *  @param port remote dcc server I2P port
     *  @param type string
     *  @return local DCC client tunnel port or -1 on error
     */
    public int newIncoming(String b32, int port, String type);

    /**
     *  An outgoing RESUME request
     *
     *  @param port local DCC client tunnel port
     *  @return remote DCC server i2p port or -1 on error
     */
    public int resumeOutgoing(int port);

    /**
     *  An incoming RESUME request
     *
     *  @param port local dcc server I2P port
     *  @return local IRC client DCC port or -1 on error
     */
    public int resumeIncoming(int port);

    /**
     *  An outgoing ACCEPT response
     *
     *  @param port local irc client DCC port
     *  @return local DCC server i2p port or -1 on error
     */
    public int acceptOutgoing(int port);

    /**
     *  An incoming ACCEPT response
     *
     *  @param port remote dcc server I2P port
     *  @return local DCC client tunnel port or -1 on error
     */
    public int acceptIncoming(int port);

}
