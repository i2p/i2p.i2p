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
 * This sends to any destination specified in send()
 *
 * @author zzz modded from I2PSink by welterde
 */
public class I2PSinkAnywhere implements Sink {
    public I2PSinkAnywhere(I2PSession sess) {
        this(sess, false);
    }
    public I2PSinkAnywhere(I2PSession sess, boolean raw) {
        this.sess = sess;
        this.raw = raw;
        
        // create maker
        if (!raw)
            this.maker.setI2PDatagramMaker(this.sess);
    }
    
    /** @param to - where it's going */
    public synchronized void send(Destination to, byte[] data) {
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
            this.sess.sendMessage(to, payload, I2PSession.PROTO_DATAGRAM,
                                  I2PSession.PORT_UNSPECIFIED, I2PSession.PORT_UNSPECIFIED);
        } catch(I2PSessionException exc) {
            // TODO: handle better
            exc.printStackTrace();
        }
    }
    
    protected boolean raw;
    protected I2PSession sess;
    protected Destination dest;
    protected final I2PDatagramMaker maker = new I2PDatagramMaker();
}
