package i2p.bote.network;

import i2p.bote.packet.dht.DhtStorablePacket;

import java.util.Collection;

import net.i2p.data.Hash;

public interface DHT {

    void store(DhtStorablePacket packet) throws Exception;
    
    DhtStorablePacket findOne(Hash key, Class<? extends DhtStorablePacket> dataType);

    Collection<DhtStorablePacket> findAll(Hash key, Class<? extends DhtStorablePacket> dataType);

    /**
     * Registers a <code>DhtStorageHandler</code> that handles incoming storage requests of a certain
     * type (but not its subclasses).
     * @param packetType
     * @param storageHandler
     */
    void setStorageHandler(Class<? extends DhtStorablePacket> packetType, DhtStorageHandler storageHandler);

    /**
     * Returns the current number of known active peers.
     * @return
     */
    int getNumPeers();
    
    void start();
    
    void shutDown();
}