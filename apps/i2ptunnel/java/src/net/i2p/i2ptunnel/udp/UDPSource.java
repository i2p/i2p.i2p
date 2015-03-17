/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package net.i2p.i2ptunnel.udp;

// system
import java.net.DatagramSocket;
import java.net.DatagramPacket;

/**
 *
 * @author welterde
 */
public class UDPSource implements Source, Runnable {
    public static final int MAX_SIZE = 15360;
    public UDPSource(int port) {
        this.sink = null;
        
        // create udp-socket
        try {
            this.sock = new DatagramSocket(port);
        } catch(Exception e) {
            throw new RuntimeException("failed to listen...", e);
        }
        
        // create thread
        this.thread = new Thread(this);
    }

    /** use socket from UDPSink */
    public UDPSource(DatagramSocket sock) {
        this.sink = null;
        this.sock = sock;
        this.thread = new Thread(this);
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
                this.sink.send(null, nbuf);
                //System.out.print("i");
            } catch(Exception e) {
                e.printStackTrace();
                break;
            }
        }
    }
    
    public void stop() {    
        this.sock.close();    
    }    
    
    
    
    
    
    
    
    
    
    
    
    
    
    protected DatagramSocket sock;
    protected Sink sink;
    protected Thread thread;
}
