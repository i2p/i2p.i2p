/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package net.i2p.i2ptunnel.udp;

// system
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

// i2p
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionListener;
import net.i2p.client.datagram.I2PDatagramDissector;

/**
 *
 * @author welterde
 */
public class I2PSource implements Source, Runnable {
    public I2PSource(I2PSession sess) {
        this(sess, true, false);
    }
    public I2PSource(I2PSession sess, boolean verify) {
        this(sess, verify, false);
    }
    public I2PSource(I2PSession sess, boolean verify, boolean raw) {
        this.sess = sess;
        this.sink = null;
        this.verify = verify;
        this.raw = raw;
        
        // create queue
        this.queue = new ArrayBlockingQueue(256);
        
        // create listener
        this.sess.setSessionListener(new Listener());
        
        // create thread
        this.thread = new Thread(this);
    }
    
    public void setSink(Sink sink) {
        this.sink = sink;
    }
    
    public void start() {
        this.thread.start();
    }
    
    public void run() {
        // create dissector
        I2PDatagramDissector diss = new I2PDatagramDissector();
        while(true) {
            try {
                // get id
                int id = this.queue.take();
                
                // receive message
                byte[] msg = this.sess.receiveMessage(id);
                
                if(!this.raw) {
                    // load datagram into it
                    diss.loadI2PDatagram(msg);
                    
                    // now call sink
                    if(this.verify)
                        this.sink.send(diss.getSender(), diss.getPayload());
                    else
                        this.sink.send(diss.extractSender(), diss.extractPayload());
                } else {
                    // verify is ignored
                    this.sink.send(null, msg);
                }
                //System.out.print("r");
            } catch(Exception e) {
                e.printStackTrace();
            }
        }
    }
    
    
    
    
    
    
    protected class Listener implements I2PSessionListener {

        public void messageAvailable(I2PSession sess, int id, long size) {
            try {
                queue.put(id);
            } catch(Exception e) {
                // ignore
            }
        }

        public void reportAbuse(I2PSession arg0, int arg1) {
            // ignore
        }

        public void disconnected(I2PSession arg0) {
            // ignore
        }

        public void errorOccurred(I2PSession arg0, String arg1, Throwable arg2) {
            // ignore
        }
        
    }
    
    
    
    
    
    
    protected I2PSession sess;
    protected BlockingQueue<Integer> queue;
    protected Sink sink;
    protected Thread thread;
    protected boolean verify;
    protected boolean raw;
}
