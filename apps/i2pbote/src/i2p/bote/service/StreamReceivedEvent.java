package i2p.bote.service;

import java.util.EventObject;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.data.Destination;

public class StreamReceivedEvent extends EventObject {
    private static final long serialVersionUID = 5231936151819853813L;
    
    private I2PSocket socket;

    public StreamReceivedEvent(I2PSocket socket) {
        super(socket.getPeerDestination());
        this.socket = socket;
    }

    public I2PSocket getSocket() {
        return socket;
    }
    
    public Destination getSource() {
        return (Destination)super.getSource();
    }
}