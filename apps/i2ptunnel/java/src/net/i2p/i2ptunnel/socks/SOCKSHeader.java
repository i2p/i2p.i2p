package net.i2p.i2ptunnel.socks;

import java.util.Arrays;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.socks.SOCKS5Constants.AddressType;
import net.i2p.util.Addresses;

/**
 * Save the SOCKS header from a datagram
 * Ref: RFC 1928
 *
 * @author zzz
 */
public class SOCKSHeader {
    private byte[] header;
    private static final byte[] beg = {0,0,0,3,60};

    /**
     * @param data the whole packet
     * @throws IllegalArgumentException on bad socks format
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
        if (addressType == AddressType.IPV4) {
            headerlen = 6 + 4;
        } else if (addressType == AddressType.DOMAINNAME) {
            headerlen = 6 + 1 + (data[4] & 0xff);
        } else if (addressType == AddressType.IPV6) {
            // future garlicat partial hash lookup possible?
            headerlen = 6 + 16;
        } else {
            throw new IllegalArgumentException("Unknown address type: " + addressType);
        }
        if (data.length < headerlen)
            throw new IllegalArgumentException("Header too short: " + data.length);

        this.header = new byte[headerlen];
        System.arraycopy(data, 0, this.header, 0, headerlen);
    }

    /**
     *  Make a dummy header from a dest,
     *  for those cases where we want to receive unsolicited datagrams.
     *  Unused for now.
     *
     *  @param port I2CP port 0-65535
     *  @since 0.9.53 add port param
     */
    public SOCKSHeader(Destination dest, int port) {
        this.header = new byte[beg.length + 60 + 2];
        System.arraycopy(beg, 0, this.header, 0, beg.length);
        String b32 = dest.toBase32();
        System.arraycopy(DataHelper.getASCII(b32), 0, this.header, beg.length, 60);
        DataHelper.toLong(header, beg.length + 60, 2, port);
    }
    
    /**
     *  As of 0.9.53, returns IP address as a string for address types 1 and 4.
     *
     *  @return hostname or null for unknown address type
     */
    public String getHost() {
        int addressType = this.header[3];
        if (addressType == AddressType.DOMAINNAME) {
            int namelen = (this.header[4] & 0xff);
            return DataHelper.getUTF8(header, 5, namelen);
        }
        if (addressType == AddressType.IPV4)
            return Addresses.toString(Arrays.copyOfRange(header, 4, 4));
        if (addressType == AddressType.IPV6)
            return Addresses.toString(Arrays.copyOfRange(header, 4, 16));
        return null;
    }
    
    /**
     *  @return 0 - 65535
     *  @since 0.9.53
     */
    public int getPort() {
        int namelen;
        int addressType = header[3];
        if (addressType == 3)
            namelen = 1 + (header[4] & 0xff);
        else if (addressType == 1)
            namelen = 4;
        else if (addressType == 4)
            namelen = 16;
        else
            return 0;
        return (int) DataHelper.fromLong(header, 4 + namelen, 2);
    }

    /**
     *  @return destination or null
     */
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
}
