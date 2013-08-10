package net.i2p.router.client;

import java.net.Socket;

import net.i2p.router.RouterContext;

/**
 *  For testing
 *
 *  @since 0.9.8
 */
class LocalClientListenerRunner extends ClientListenerRunner {

    public LocalClientListenerRunner(RouterContext context, ClientManager manager, int port) {
        super(context, manager, port);
    }

    @Override
    protected void runConnection(Socket socket) {
        ClientConnectionRunner runner = new LocalClientConnectionRunner(_context, _manager, socket);
        _manager.registerConnection(runner);
    }
}
