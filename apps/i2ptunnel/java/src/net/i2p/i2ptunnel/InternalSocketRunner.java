package net.i2p.i2ptunnel;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import net.i2p.util.I2PAppThread;
import net.i2p.util.InternalServerSocket;
import net.i2p.util.Log;

/**
 * Listen for in-JVM connections on the internal "socket"
 *
 * @author zzz
 * @since 0.7.9
 */
class InternalSocketRunner extends I2PAppThread {
    private final I2PTunnelClientBase client;
    private final int port;
    private ServerSocket ss;
    private volatile boolean open;

    /**
     * Does not start the runner, caller must call start()
     */
    InternalSocketRunner(I2PTunnelClientBase client) {
        super("Internal socket port " + client.getLocalPort());
        setDaemon(true);
        this.client = client;
        this.port = client.getLocalPort();
    }
    
    @Override
    public final void run() {
        try {
            this.ss = new InternalServerSocket(this.port);
            this.open = true;
            while (this.open) {
                Socket s = this.ss.accept();
                this.client.manageConnection(s);
            }
        } catch (IOException ex) {
            if (this.open) {
                Log log = new Log(InternalSocketRunner.class);
                log.error("Error listening for internal connections on port " + this.port, ex);
                stopRunning();
            }
        }
    }

    void stopRunning() {
        if (this.open) {
            this.open = false;
            try {
                this.ss.close();
            } catch (IOException ex) {}
        }
    }
}
