package net.i2p.util;

import net.i2p.I2PAppContext;
import net.i2p.data.Base32;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.data.Hash;

/**
 *  Convert any kind of destination String to a hash
 *  Supported:
 *    Base64 dest
 *    Base64 dest.i2p
 *    Base64 Hash
 *    Base32 Hash
 *    Base32 desthash.b32.i2p
 *    example.i2p
 *
 *  @author zzz
 */
public class ConvertToHash {
    
    /**
     *  Convert any kind of destination String to a hash
     *
     *  @return null on failure
     */
    public static Hash getHash(String peer) {
        if (peer == null)
            return null;
        Hash h = new Hash();
        String peerLC = peer.toLowerCase();
        // b64 hash
        if (peer.length() == 44 && !peerLC.endsWith(".i2p")) {
            try {
                h.fromBase64(peer);
            } catch (DataFormatException dfe) {}
        }
        // b64 dest.i2p
        if (h.getData() == null && peer.length() >= 520 && peerLC.endsWith(".i2p")) {
            try {
                Destination d = new Destination();
                d.fromBase64(peer.substring(0, peer.length() - 4));
                h = d.calculateHash();
            } catch (DataFormatException dfe) {}
        }
        // b64 dest
        if (h.getData() == null && peer.length() >= 516 && !peerLC.endsWith(".i2p")) {
            try {
                Destination d = new Destination();
                d.fromBase64(peer);
                h = d.calculateHash();
            } catch (DataFormatException dfe) {}
        }
        // b32 hash.b32.i2p
        // do this here rather than in naming service so it will work
        // even if the leaseset is not found
        if (h.getData() == null && peer.length() == 60 && peerLC.endsWith(".b32.i2p")) {
            byte[] b = Base32.decode(peer.substring(0, 52));
            if (b != null && b.length == Hash.HASH_LENGTH)
                h.setData(b);
        }
        // b32 hash
        if (h.getData() == null && peer.length() == 52 && !peerLC.endsWith(".i2p")) {
            byte[] b = Base32.decode(peer);
            if (b != null && b.length == Hash.HASH_LENGTH)
                h.setData(b);
        }
        // example.i2p
        if (h.getData() == null) {
            Destination d = I2PAppContext.getGlobalContext().namingService().lookup(peer);
            if (d != null)
                h = d.calculateHash();
        }
        if (h.getData() == null)
            return null;
        return h;
    }
}
