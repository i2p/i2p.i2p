package net.i2p.app;

import java.io.IOException;
import java.net.Socket;

/**
 *
 *  @since 0.9.11
 */
public interface Outproxy {

    public static final String NAME = "outproxy";

    /**
     *
     */
    public Socket connect(String host, int port) throws IOException;

}
