package i2p.bote.network;

import i2p.bote.packet.CommunicationPacket;
import net.i2p.data.Destination;

public class PacketBatchItem {
    private CommunicationPacket packet;
    private Destination destination;
    private boolean confirmed;
    
    PacketBatchItem(CommunicationPacket packet, Destination destination) {
        this.packet = packet;
        this.destination = destination;
        confirmed = false;
    }
    
    public CommunicationPacket getPacket() {
        return packet;
    }
    
    public Destination getDestination() {
        return destination;
    }

    void confirmDelivery() {
        confirmed = true;
    }
    
    boolean isDeliveryConfirmed() {
        return confirmed;
    }
}