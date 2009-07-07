package net.i2p.router.transport.udp;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;

/**
 * Unique ID for a peer - its IP + port, all bundled into a tidy obj.
 * If the remote peer is not reachabe through an IP+port, this contains
 * the hash of their identity.
 *
 */
final class RemoteHostId {
    private byte _ip[];
    private int _port;
    private byte _peerHash[];
    
    public RemoteHostId(byte ip[], int port) {
        _ip = ip;
        _port = port;
    }
    public RemoteHostId(byte peerHash[]) {
        _peerHash = peerHash;
    }
    
    public byte[] getIP() { return _ip; }
    public int getPort() { return _port; }
    public byte[] getPeerHash() { return _peerHash; }
    
    @Override
    public int hashCode() {
        int rv = 0;
        for (int i = 0; _ip != null && i < _ip.length; i++)
            rv += _ip[i] << i;
        for (int i = 0; _peerHash != null && i < _peerHash.length; i++)
            rv += _peerHash[i] << i;
        rv += _port;
        return rv;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (obj == null) 
            throw new NullPointerException("obj is null");
        if (!(obj instanceof RemoteHostId)) 
            throw new ClassCastException("obj is a " + obj.getClass().getName());
        RemoteHostId id = (RemoteHostId)obj;
        return (_port == id.getPort()) && DataHelper.eq(_ip, id.getIP()) && DataHelper.eq(_peerHash, id.getPeerHash());
    }
    
    @Override
    public String toString() { return toString(true); }
    public String toString(boolean includePort) {
        if (_ip != null) {
            if (includePort)
                return toString(_ip) + ':' + _port;
            else
                return toString(_ip);
        } else {
            return Base64.encode(_peerHash);
        }
    }
    public static String toString(byte ip[]) {
        StringBuilder buf = new StringBuilder(ip.length+5);
        for (int i = 0; i < ip.length; i++) {
            buf.append(ip[i]&0xFF);
            if (i + 1 < ip.length)
                buf.append('.');
        }
        return buf.toString();
    }
    public String toHostString() { return toString(false); }
}
