package net.i2p.i2ptunnel.udp;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.DatagramPacket;

import net.i2p.I2PAppContext;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 *
 * @author welterde
 */
public class UDPSource implements Source, Runnable {
    protected final DatagramSocket sock;
    protected Sink sink;
    protected final Thread thread;
    private final int port;
    public static final int MAX_SIZE = 15360;

    /**
     *  @throws RuntimeException on DatagramSocket IOException
     */
    public UDPSource(int port) {
        // create udp-socket
        try {
            this.sock = new DatagramSocket(port);
        } catch (IOException e) {
            throw new RuntimeException("failed to listen...", e);
        }
        this.port = port;
        // create thread
        this.thread = new I2PAppThread(this);
    }

    /** use socket from UDPSink */
    public UDPSource(DatagramSocket sock) {
        this.sock = sock;
        port = sock.getLocalPort();    
        this.thread = new I2PAppThread(this);
    }
    
    public void setSink(Sink sink) {
        this.sink = sink;
    }
    
    public void start() {
        this.thread.start();
    }
    
    public void run() {
        // create packet
        byte[] buf = new byte[MAX_SIZE];
        DatagramPacket pack = new DatagramPacket(buf, buf.length);
        while(true) {
            try {
                // receive...
                this.sock.receive(pack);
                
                // create new data array
                byte[] nbuf = new byte[pack.getLength()];
                
                // copy over
                System.arraycopy(pack.getData(), 0, nbuf, 0, nbuf.length);
                
                // transfer to sink
                this.sink.send(null, port, 0, nbuf);
                //System.out.print("i");
            } catch(Exception e) {
                Log log = I2PAppContext.getGlobalContext().logManager().getLog(getClass());
                if (log.shouldWarn())
                    log.warn("error sending", e);
                break;
            }
        }
    }
    
    /**
     *  @return the local port of the DatagramSocket we are receiving on
     *  @since 0.9.53
     */
    public int getPort() {    
        return port;    
    }    

    public void stop() {    
        this.sock.close();    
    }    
}
