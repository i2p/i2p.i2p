package net.i2p.router.transport.udp;

import net.i2p.data.SessionKey;

/**
 * Describe the offering to act as an introducer
 *
 */
class RelayPeer {
    private String _host;
    private int _port;
    private byte _tag[];
    private SessionKey _relayIntroKey;
    public RelayPeer(String host, int port, byte tag[], SessionKey introKey) {
        _host = host;
        _port = port;
        _tag = tag;
        _relayIntroKey = introKey;
    }
    public String getHost() { return _host; }
    public int getPort() { return _port; }
    public byte[] getTag() { return _tag; }
    public SessionKey getIntroKey() { return _relayIntroKey; }
}
