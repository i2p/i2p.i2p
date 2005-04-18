/*
 * QUtil.java
 *
 * Created on April 6, 2005, 2:11 PM
 */

package net.i2p.aum.q;

import java.*;

import net.i2p.*;
import net.i2p.data.*;

/**
 * A general collection of static utility methods
 */
public class QUtil {
    
    public static boolean debugEnabled = true;

    /**
     * Generates a new secure space public/private keypair
     * @return an array of 2 strings, first one is SSK Public Key, second one
     * is SSK Private Key.
     */
    public static String [] newKeys() {
        Object [] keypair = I2PAppContext.getGlobalContext().keyGenerator().generateSigningKeypair();
        SigningPublicKey pub = (SigningPublicKey)keypair[0];
        SigningPrivateKey priv = (SigningPrivateKey)keypair[1];
        String [] sskKeypair = new String[2];
        sskKeypair[0] = hashPubKey(pub);
        sskKeypair[1] = priv.toBase64();
        return sskKeypair;
    }

    /**
     * converts a signed space private key (in base64)
     * to its base64 ssk public equivalent
     * @param priv64 SSK private key string as base64
     * @return public key, base64-encoded
     */
    public static String privateToPubHash(String priv)
        throws DataFormatException
    {
        return hashPubKey(new SigningPrivateKey(priv).toPublic());
    }

    public static SigningPublicKey privateToPublic(String priv64)
        throws DataFormatException
    {
        SigningPrivateKey priv = new SigningPrivateKey(priv64);
        SigningPublicKey pub = priv.toPublic();
        return pub;
    }

    public static String hashPubKey(String pub64)
        throws DataFormatException
    {
        return hashPubKey(new SigningPublicKey(pub64));
    }

    /**
     * hashes a public key for use in signed space keypairs
     * possibly shorten this
     */
    public static String hashPubKey(SigningPublicKey pub) {
        String hashed = sha64(pub.toByteArray());
        String abbrev = hashed.substring(0, 24);
        return abbrev;
    }

    /**
     * returns base64 of sha hash of a string
     */
    public static String sha64(String raw) {
        return sha64(raw.getBytes());
    }

    public static String sha64(byte [] raw) {
        //return stripEquals(Base64.encode(sha(raw)));
        return Base64.encode(sha(raw)).replaceAll("[=]", "");
    }

    public static byte [] sha(String raw) {
        return sha(raw.getBytes());
    }

    public static byte [] sha(byte [] raw) {
        return I2PAppContext.getGlobalContext().sha().calculateHash(raw).getData();
    }

    public static void debug(String s) {
        if (debugEnabled) {
            System.out.println("QSSL:"+s);
        }
    }

}
