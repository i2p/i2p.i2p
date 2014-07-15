package net.i2p.router.client;

import java.io.IOException;
import java.net.ServerSocket;

import net.i2p.client.DomainSocketFactory;
import net.i2p.router.RouterContext;

/**
 * Unix domain socket version of ClientListenerRunner.
 * This is a stub that does nothing.
 * This class is replaced in the Android build.
 *
 * @since 0.9.15
 */
public class DomainClientListenerRunner extends ClientListenerRunner {
    public DomainClientListenerRunner(RouterContext context, ClientManager manager) {
        super(context, manager, -1);
    }

    /**
     * @throws IOException
     */
    @Override
    protected ServerSocket getServerSocket() throws IOException {
        final DomainSocketFactory fact = new DomainSocketFactory(_context);
        return fact.createServerSocket(DomainSocketFactory.I2CP_SOCKET_ADDRESS);
    }
}
