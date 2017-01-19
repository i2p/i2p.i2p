package net.i2p.util;

import java.util.Locale;

import net.i2p.I2PAppContext;
import net.i2p.data.Base32;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.data.Hash;

/**
 *  Convert any kind of destination String to a hash
 *  Supported:
 *    Base64 dest
 *    Base64 dest.i2p
 *    Base64 Hash
 *    Base64 Hash.i2p
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
        String peerLC = peer.toLowerCase(Locale.US);
        // b64 hash
        if (peer.length() == 44 && !peerLC.endsWith(".i2p")) {
            byte[] b = Base64.decode(peer);
            if (b != null && b.length == Hash.HASH_LENGTH)
                return Hash.create(b);
        }
        // b64 hash.i2p
        if (peer.length() == 48 && peerLC.endsWith(".i2p")) {
            byte[] b = Base64.decode(peer.substring(0, 44));
            if (b != null && b.length == Hash.HASH_LENGTH)
                return Hash.create(b);
        }
        // b64 dest.i2p
        if (peer.length() >= 520 && peerLC.endsWith(".i2p")) {
            try {
                Destination d = new Destination();
                d.fromBase64(peer.substring(0, peer.length() - 4));
                return d.calculateHash();
            } catch (DataFormatException dfe) {}
        }
        // b64 dest
        if (peer.length() >= 516 && !peerLC.endsWith(".i2p")) {
            try {
                Destination d = new Destination();
                d.fromBase64(peer);
                return d.calculateHash();
            } catch (DataFormatException dfe) {}
        }
        // b32 hash.b32.i2p
        // do this here rather than in naming service so it will work
        // even if the leaseset is not found
        if (peer.length() == 60 && peerLC.endsWith(".b32.i2p")) {
            byte[] b = Base32.decode(peer.substring(0, 52));
            if (b != null && b.length == Hash.HASH_LENGTH)
                return Hash.create(b);
        }
        // b32 hash
        if (peer.length() == 52 && !peerLC.endsWith(".i2p")) {
            byte[] b = Base32.decode(peer);
            if (b != null && b.length == Hash.HASH_LENGTH)
                return Hash.create(b);
        }
        // example.i2p
        Destination d = I2PAppContext.getGlobalContext().namingService().lookup(peer);
        if (d != null)
            return d.calculateHash();

        return null;
    }

    /**
     * @since 0.9.28
     */
    public static void main(String args[]) {
        if (args.length == 0) {
            System.err.println("Usage: converttohash [hostname|b32|destination]...");
            return;
        }
        for (int i = 0; i < args.length; i++) {
            Hash h = getHash(args[i]);
            System.out.println(h != null ? h.toBase64() : "conversion failed");
        }
    }
}
