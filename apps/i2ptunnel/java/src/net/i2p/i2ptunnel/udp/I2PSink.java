/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package net.i2p.i2ptunnel.udp;

// i2p
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.data.Destination;
import net.i2p.client.datagram.I2PDatagramMaker;

/**
 * Producer
 *
 * This sends to a fixed destination specified in the constructor
 *
 * @author welterde
 */
public class I2PSink implements Sink {
    public I2PSink(I2PSession sess, Destination dest) {
        this(sess, dest, false);
    }
    public I2PSink(I2PSession sess, Destination dest, boolean raw) {
        this.sess = sess;
        this.dest = dest;
        this.raw = raw;
        
        // create maker
        if (!raw)
            this.maker.setI2PDatagramMaker(this.sess);
    }
    
    /** @param src ignored */
    public synchronized void send(Destination src, byte[] data) {
        //System.out.print("w");
        // create payload
        byte[] payload;
        if(!this.raw) {
            synchronized(this.maker) {
                payload = this.maker.makeI2PDatagram(data);
            }
        } else
            payload = data;
        
        // send message
        try {
            this.sess.sendMessage(this.dest, payload, I2PSession.PROTO_DATAGRAM,
                                  I2PSession.PORT_UNSPECIFIED, I2PSession.PORT_UNSPECIFIED);
        } catch(I2PSessionException exc) {
            // TODO: handle better
            exc.printStackTrace();
        }
    }
    
    protected boolean raw;
    protected I2PSession sess;
    protected Destination dest;
    protected final I2PDatagramMaker maker= new I2PDatagramMaker(); // FIXME should be final and use a factory. FIXME
}
