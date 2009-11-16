package i2p.bote.network;

import i2p.bote.packet.CommunicationPacket;
import i2p.bote.packet.I2PBotePacket;
import i2p.bote.packet.UniqueId;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import net.i2p.data.Destination;
import net.i2p.util.ConcurrentHashSet;

/**
 * This class is used for sending a number of packets to other nodes,
 * and collecting the response packets.
 * 
 * @author HungryHobo@mail.i2p
 *
 */
// TODO use I2PSendQueue.sendAndWait(), get rid of PacketBatch.sentSignal, etc?
public class PacketBatch implements Iterable<PacketBatchItem> {
    private Map<UniqueId, PacketBatchItem> outgoingPackets;
    private Set<I2PBotePacket> incomingPackets;
    private CountDownLatch sentSignal;   // this field is lazy-initialized
    private CountDownLatch firstReplyReceivedSignal;

    public PacketBatch() {
        outgoingPackets = new ConcurrentHashMap<UniqueId, PacketBatchItem>();
        incomingPackets = new ConcurrentHashSet<I2PBotePacket>();
        firstReplyReceivedSignal = new CountDownLatch(1);
    }
    
    public void putPacket(CommunicationPacket packet, Destination destination) {
        outgoingPackets.put(packet.getPacketId(), new PacketBatchItem(packet, destination));
    }

    /**
     * Return <code>true</code> if the batch contains a packet with a given {@link UniqueId}.
     * @param packetId
     * @return
     */
    public boolean contains(final UniqueId packetId) {
        return outgoingPackets.containsKey(packetId);
    }
    
    public int getPacketCount() {
        return outgoingPackets.keySet().size();
    }

    public Iterator<PacketBatchItem> iterator() {
        return outgoingPackets.values().iterator();
    }
    
/*    private void decrementConfirmedLatch() {
        initSignals();
        confirmedSignal.countDown();
    }

    boolean areAllPacketsConfirmed() {
        return confirmedSignal.getCount() == 0;
    }

    boolean isPacketConfirmed(UniqueId packetId) {
        decrementConfirmedLatch();
        return packetMap.get(packetId).isDeliveryConfirmed();
    }*/

    /**
     * Notify the <code>PacketBatch</code> that delivery confirmation has been received for
     * a packet.
     * @param packetId
     */
/*    void confirmDelivery(UniqueId packetId) {
        if (outgoingPackets.containsKey(packetId))
            outgoingPackets.get(packetId).confirmDelivery();
    }*/
    
    void addResponsePacket(I2PBotePacket packet) {
        incomingPackets.add(packet);
        firstReplyReceivedSignal.countDown();
    }
    
    public Set<I2PBotePacket> getResponsePackets() {
        return incomingPackets;
    }
    
    void decrementSentLatch() {
        getSentSignal().countDown();
    }
    
    private synchronized CountDownLatch getSentSignal() {
        if (sentSignal == null)
            sentSignal = new CountDownLatch(getPacketCount());
        return sentSignal;
    }
    
    public void awaitSendCompletion() throws InterruptedException {
        getSentSignal().await(5, TimeUnit.MINUTES);
    }
    
    public void awaitFirstReply(long timeout, TimeUnit timeoutUnit) throws InterruptedException {
        firstReplyReceivedSignal.await(timeout, timeoutUnit);
    }
}