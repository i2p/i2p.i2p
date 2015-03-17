package net.i2p.router.transport.udp;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.util.Addresses;

/**
 * Unique ID for a peer - its IP + port, all bundled into a tidy obj.
 * If the remote peer is not reachable through an IP+port, this contains
 * the hash of their identity.
 *
 */
final class RemoteHostId {
    private final byte _ip[];
    private final int _port;
    private final byte _peerHash[];
    private final int _hashCode;
    
    /** direct */
    public RemoteHostId(byte ip[], int port) {
        this(ip, port, null);
    }

    /** indirect */
    public RemoteHostId(byte peerHash[]) {
        this(null, 0, peerHash);
    }
    
    private RemoteHostId(byte ip[], int port, byte peerHash[]) {
        _ip = ip;
        _port = port;
        _peerHash = peerHash;
        _hashCode = DataHelper.hashCode(_ip) ^ DataHelper.hashCode(_peerHash) ^ _port;
    }

    /** @return null if indirect */
    public byte[] getIP() { return _ip; }

    /** @return 0 if indirect */
    public int getPort() { return _port; }

    /** @return null if direct */
    public byte[] getPeerHash() { return _peerHash; }
    
    @Override
    public int hashCode() {
        return _hashCode;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (obj == null) 
            return false;
        if (!(obj instanceof RemoteHostId)) 
            return false;
        RemoteHostId id = (RemoteHostId)obj;
        return (_port == id._port) && DataHelper.eq(_ip, id._ip) && DataHelper.eq(_peerHash, id._peerHash);
    }
    
    @Override
    public String toString() { return toString(true); }

    private String toString(boolean includePort) {
        if (_ip != null) {
            if (includePort)
                return Addresses.toString(_ip, _port);
            else
                return Addresses.toString(_ip);
        } else {
            return Base64.encode(_peerHash);
        }
    }

    public String toHostString() { return toString(false); }
}
