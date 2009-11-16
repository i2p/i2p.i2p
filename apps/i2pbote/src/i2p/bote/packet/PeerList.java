package i2p.bote.packet;

import i2p.bote.network.kademlia.KademliaPeer;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;

import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.Log;

@TypeCode('L')
public class PeerList extends DataPacket {
    private Log log = new Log(PeerList.class);
    // TODO should be a Collection<Destination> because this class will also be used for relay peer lists
    private Collection<KademliaPeer> peers;

    public PeerList(Collection<KademliaPeer> peers) {
        this.peers = peers;
    }

    public PeerList(byte[] data) throws DataFormatException {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        if (buffer.get() != getPacketTypeCode())
            log.error("Wrong type code for PeerList. Expected <" + getPacketTypeCode() + ">, got <" + (char)data[0] + ">");
        
        int numPeers = buffer.getShort();

        peers = new ArrayList<KademliaPeer>();
        for (int i=0; i<numPeers; i++) {
            Destination destination = new Destination();
            byte[] peerData = new byte[388];
            // read 384 bytes, leave the last 3 bytes zero
            buffer.get(peerData, 0, 384);
            
            destination.readBytes(peerData, 0);
            KademliaPeer peer = new KademliaPeer(destination, 0);
            peers.add(peer);
        }
        
        if (buffer.hasRemaining())
            log.debug("Peer List has " + buffer.remaining() + " extra bytes.");
    }
    
    public Collection<KademliaPeer> getPeers() {
        return peers;
    }
    
    @Override
    public byte[] toByteArray() {
        ByteArrayOutputStream arrayOutputStream = new ByteArrayOutputStream();
        DataOutputStream dataStream = new DataOutputStream(arrayOutputStream);
        
        try {
            dataStream.write((byte)getPacketTypeCode());
            dataStream.writeShort(peers.size());
            for (KademliaPeer peer: peers)
                dataStream.write(peer.getDestination().toByteArray());
        }
        catch (IOException e) {
            log.error("Can't write to ByteArrayOutputStream.", e);
        }
        
        return arrayOutputStream.toByteArray();
    }
}