package i2p.bote.folder;

import i2p.bote.packet.I2PBotePacket;
import i2p.bote.packet.IndexPacket;
import i2p.bote.packet.dht.DhtStorablePacket;

import java.io.File;

import net.i2p.util.Log;

/**
 * This class differs from {@link DhtPacketFolder} in that it doesn't overwrite an existing
 * packet when a new packet is stored under the same key, but 
 *
 * @author HungryHobo@mail.i2p
 */
public class IndexPacketFolder extends DhtPacketFolder<IndexPacket> {
    private final Log log = new Log(I2PBotePacket.class);

    public IndexPacketFolder(File storageDir) {
        super(storageDir);
    }

    @Override
    public void store(DhtStorablePacket packetToStore) {
        if (!(packetToStore instanceof IndexPacket))
            throw new IllegalArgumentException("This class only stores packets of type " + IndexPacket.class.getSimpleName() + ".");
        
        IndexPacket indexPacketToStore = (IndexPacket)packetToStore;
        DhtStorablePacket existingPacket = retrieve(packetToStore.getDhtKey());
        
        // If an index packet with the same key exists in the folder, merge the two packets.
        if (existingPacket instanceof IndexPacket) {
            packetToStore = new IndexPacket(indexPacketToStore, (IndexPacket)existingPacket);
            if (packetToStore.isTooBig())
                // TODO make two new index packets, put half the email packet keys in each one, store the two index packets on the DHT, and put the two index packet keys into the local index file (only keep those two).
                log.error("After merging, IndexPacket is too big for a datagram: size=" + packetToStore.getSize());
        }
        
        super.store(packetToStore);
    }
}