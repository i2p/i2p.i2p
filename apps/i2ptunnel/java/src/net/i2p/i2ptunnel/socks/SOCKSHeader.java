package net.i2p.i2ptunnel.socks;

import net.i2p.I2PAppContext;
import net.i2p.data.Base32;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.I2PTunnel;

/**
 * Save the SOCKS header from a datagram
 * Ref: RFC 1928
 *
 * @author zzz
 */
public class SOCKSHeader {

    /**
     * @param data the whole packet
     */
    public SOCKSHeader(byte[] data) {
        if (data.length <= 8)
            throw new IllegalArgumentException("Header too short: " + data.length);
        if (data[0] != 0 || data[1] != 0)
            throw new IllegalArgumentException("Not a SOCKS datagram?");
        if (data[2] != 0)
            throw new IllegalArgumentException("We can't handle fragments!");
        int headerlen = 0;
        int addressType = data[3];
        if (addressType == 1) {
            // this will fail in getDestination()
            headerlen = 6 + 4;
        } else if (addressType == 3) {
            headerlen = 6 + 1 + (data[4] & 0xff);
        } else if (addressType == 4) {
            // this will fail in getDestination()
            // but future garlicat partial hash lookup possible?
            headerlen = 6 + 16;
        } else {
            throw new IllegalArgumentException("Unknown address type: " + addressType);
        }
        if (data.length < headerlen)
            throw new IllegalArgumentException("Header too short: " + data.length);

        this.header = new byte[headerlen];
        System.arraycopy(this.header, 0, data, 0, headerlen);
    }
    
    private static final byte[] beg = {0,0,0,3,60};
    private static final byte[] end = {'.','b','3','2','.','i','2','p',0,0};

    /**
     *  Make a dummy header from a dest,
     *  for those cases where we want to receive unsolicited datagrams.
     *  Unused for now.
     */
    public SOCKSHeader(Destination dest) {
        this.header = new byte[beg.length + 52 + end.length];
        System.arraycopy(this.header, 0, beg, 0, beg.length);
        String b32 = Base32.encode(dest.calculateHash().getData());
        System.arraycopy(this.header, beg.length, b32.getBytes(), 0, 52);
        System.arraycopy(this.header, beg.length + 52, end, 0, end.length);
    }
    
    public String getHost() {
        int addressType = this.header[3];
        if (addressType != 3)
            return null;
        int namelen = (this.header[4] & 0xff);
        byte[] nameBytes = new byte[namelen];
        System.arraycopy(nameBytes, 0, this.header, 5, namelen);
        return new String(nameBytes);
    }

    public Destination getDestination() {
        String name = getHost();
        if (name == null)
            return null;
        // the naming service does caching (thankfully)
        return I2PAppContext.getGlobalContext().namingService().lookup(name);
    }

    public byte[] getBytes() {
        return header;
    }

    private byte[] header;
}
