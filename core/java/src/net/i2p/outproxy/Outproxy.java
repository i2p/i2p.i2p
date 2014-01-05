package net.i2p.outproxy;

import java.io.IOException;
import java.net.Socket;

/**
 *
 *  @since 0.9.11
 */
public interface Outproxy {

    /**
     *
     */
    public Socket connect(String host, int port) throws IOException;

}
