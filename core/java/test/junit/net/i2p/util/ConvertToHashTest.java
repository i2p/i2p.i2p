package net.i2p.util;

import net.i2p.data.Hash;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * @since 0.9.49
 */
public class ConvertToHashTest {
    private static final String zzzDotI2pBase32Hash = "lhbd7ojcaiofbfku7ixh47qj537g572zmhdc4oilvugzxdpdghua";
    private static final String zzzDotI2pBase64Hash = "WcI~uSICHFCVVPoufn4J7v5u~1lhxi45C60Nm43jMeg=";
    private static final String zzzDotI2pBase64Dest = "GKapJ8koUcBj~jmQzHsTYxDg2tpfWj0xjQTzd8BhfC9c3OS5fwPBNajgF-eOD6eCjFTqTlorlh7Hnd8kXj1qblUGXT-tDoR9~YV8dmXl51cJn9MVTRrEqRWSJVXbUUz9t5Po6Xa247Vr0sJn27R4KoKP8QVj1GuH6dB3b6wTPbOamC3dkO18vkQkfZWUdRMDXk0d8AdjB0E0864nOT~J9Fpnd2pQE5uoFT6P0DqtQR2jsFvf9ME61aqLvKPPWpkgdn4z6Zkm-NJOcDz2Nv8Si7hli94E9SghMYRsdjU-knObKvxiagn84FIwcOpepxuG~kFXdD5NfsH0v6Uri3usE3XWD7Pw6P8qVYF39jUIq4OiNMwPnNYzy2N4mDMQdsdHO3LUVh~DEppOy9AAmEoHDjjJxt2BFBbGxfdpZCpENkwvmZeYUyNCCzASqTOOlNzdpne8cuesn3NDXIpNnqEE6Oe5Qm5YOJykrX~Vx~cFFT3QzDGkIjjxlFBsjUJyYkFjBQAEAAcAAA==";


    @Test
    public void getHashNullPeer() {
        assertNull(ConvertToHash.getHash(null));
    }

    @Test
    public void getHashB64() {
        Hash hash = ConvertToHash.getHash(zzzDotI2pBase64Hash);
        assertNotNull(hash);
        assertEquals(hash.toBase64(), zzzDotI2pBase64Hash);
    }

    @Test
    public void getHashB64DotI2P() {
        Hash hash = ConvertToHash.getHash(zzzDotI2pBase64Hash + ".i2p");
        assertNotNull(hash);
        assertEquals(hash.toBase64(), zzzDotI2pBase64Hash);
    }

    @Test
    public void getHashDestinationB64() {
        Hash hash = ConvertToHash.getHash(zzzDotI2pBase64Dest);
        assertNotNull(hash);
        assertEquals(hash.toBase64(), zzzDotI2pBase64Hash);
    }

    @Test
    public void getHashDestinationB64DotI2P() {
        Hash hash = ConvertToHash.getHash(zzzDotI2pBase64Dest + ".i2p");
        assertNotNull(hash);
        assertEquals(hash.toBase64(), zzzDotI2pBase64Hash);
    }

    @Test
    public void getHashB32() {
        Hash hash = ConvertToHash.getHash(zzzDotI2pBase32Hash);
        assertNotNull(hash);
        assertEquals(hash.toBase32(), zzzDotI2pBase32Hash + ".b32.i2p");
    }

    @Test
    public void getHashB32DotI2P() {
        String zzzB32I2P = zzzDotI2pBase32Hash + ".b32.i2p";
        Hash hash = ConvertToHash.getHash(zzzB32I2P);
        assertNotNull(hash);
        assertEquals(hash.toBase32(), zzzB32I2P);
    }

    /**
     * The case where a destination cannot be resolved at all
     */
    @Test
    public void getHashResolveDestinationFail() {
        assertNull(ConvertToHash.getHash("unknown.i2p"));
    }
}
