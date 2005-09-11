package net.i2p.router.transport.udp;

import net.i2p.data.DataHelper;

/**
 * Unique ID for a peer - its IP + port, all bundled into a tidy obj.
 * Aint it cute?
 *
 */
final class RemoteHostId {
    private byte _ip[];
    private int _port;
    
    public RemoteHostId(byte ip[], int port) {
        _ip = ip;
        _port = port;
    }
    
    public byte[] getIP() { return _ip; }
    public int getPort() { return _port; }
    
    public int hashCode() {
        int rv = 0;
        for (int i = 0; i < _ip.length; i++)
            rv += _ip[i] << i;
        rv += _port;
        return rv;
    }
    
    public boolean equals(Object obj) {
        if (obj == null) 
            throw new NullPointerException("obj is null");
        if (!(obj instanceof RemoteHostId)) 
            throw new ClassCastException("obj is a " + obj.getClass().getName());
        RemoteHostId id = (RemoteHostId)obj;
        return (_port == id.getPort()) && DataHelper.eq(_ip, id.getIP());
    }
    
    public String toString() { return toString(true); }
    public String toString(boolean includePort) {
        if (includePort)
            return toString(_ip) + ':' + _port;
        else
            return toString(_ip);
    }
    public static String toString(byte ip[]) {
        StringBuffer buf = new StringBuffer(ip.length+5);
        for (int i = 0; i < ip.length; i++) {
            buf.append(ip[i]&0xFF);
            if (i + 1 < ip.length)
                buf.append('.');
        }
        return buf.toString();
    }
    public String toHostString() { return toString(false); }
}
