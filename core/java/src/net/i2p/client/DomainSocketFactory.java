package net.i2p.client;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import net.i2p.I2PAppContext;

/**
 * Bridge to Unix domain socket (or similar).
 * <p/>
 * This is a stub that does nothing.
 * This class is replaced in the Android build.
 *
 * @author str4d
 * @since 0.9.14
 */
public class DomainSocketFactory {
    public static final String I2CP_SOCKET_ADDRESS = "net.i2p.client.i2cp";

    /**
     * @throws UnsupportedOperationException always
     */
    public DomainSocketFactory(I2PAppContext context) {
        throw new UnsupportedOperationException();
    }

    /**
     * Override in Android.
     * @throws IOException
     * @throws UnsupportedOperationException always
     */
    public Socket createSocket(String name) throws IOException {
        throw new UnsupportedOperationException();
    }

    /**
     * Override in Android.
     * @throws IOException
     * @throws UnsupportedOperationException always
     */
    public ServerSocket createServerSocket(String name) throws IOException {
        throw new UnsupportedOperationException();
    }
}
