package i2p.bote.network;

import i2p.bote.packet.dht.DhtStorablePacket;
import net.i2p.data.Hash;

/**
 * Defines methods for accessing a local DHT store.
 *
 * @author HungryHobo@mail.i2p
 */
public interface DhtStorageHandler {

    void store(DhtStorablePacket packetToStore);
    
    // Retrieves a packet by DHT key. If no matching packet is found, <code>null</code> is returned.
    DhtStorablePacket retrieve(Hash dhtKey);
}