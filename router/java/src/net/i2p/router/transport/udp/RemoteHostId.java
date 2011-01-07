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
        return DataHelper.hashCode(_ip) ^ DataHelper.hashCode(_peerHash) ^ _port;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (obj == null) 
            return false;
        if (!(obj instanceof RemoteHostId)) 
            return false;
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
