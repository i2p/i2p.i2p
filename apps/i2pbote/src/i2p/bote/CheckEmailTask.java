package i2p.bote;

import i2p.bote.folder.IncompleteEmailFolder;
import i2p.bote.network.DHT;
import i2p.bote.network.PeerManager;
import i2p.bote.packet.EmailPacket;
import i2p.bote.packet.IndexPacket;
import i2p.bote.packet.UniqueId;
import i2p.bote.packet.dht.DhtStorablePacket;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.util.Log;

/**
 * Gets email packets from the DHT for one email identity. A separate thread is used for
 * each packet in order to speed things up, and because the packets are in different places
 * on the network.
 *
 * @author HungryHobo@mail.i2p
 */
public class CheckEmailTask implements Runnable {
    private static final int MAX_THREADS = 50;
    private static final int THREAD_STACK_SIZE = 64 * 1024;   // TODO find a safe low value (default in 64-bit Java 1.6 = 1MByte)
    private static final ThreadFactory EMAIL_PACKET_TASK_THREAD_FACTORY = Util.createThreadFactory("EmailPktTask", THREAD_STACK_SIZE);
    private ExecutorService executor;
    
    private Log log = new Log(CheckEmailTask.class);
    private EmailIdentity identity;
    private DHT dht;
    private PeerManager peerManager;
    private IncompleteEmailFolder incompleteEmailFolder;

    public CheckEmailTask(EmailIdentity identity, DHT dht, PeerManager peerManager, IncompleteEmailFolder incompleteEmailFolder) {
        this.identity = identity;
        this.dht = dht;
        this.peerManager = peerManager;
        this.incompleteEmailFolder = incompleteEmailFolder;
    }
    
    @Override
    public void run() {
        Collection<Hash> emailPacketKeys = findEmailPacketKeys();
        
        executor = Executors.newFixedThreadPool(MAX_THREADS, EMAIL_PACKET_TASK_THREAD_FACTORY);
        for (Hash dhtKey: emailPacketKeys)
            executor.submit(new EmailPacketTask(dhtKey));
        executor.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.DAYS);
        } catch (InterruptedException e) {
            log.error("Interrupted while checking for mail.", e);
            executor.shutdownNow();
        }
    }
    
    /**
     * Queries the DHT for new index packets and returns the DHT keys contained in them.
     * @return A <code>Collection</code> containing zero or more elements
     */
    private Collection<Hash> findEmailPacketKeys() {
        log.debug("Querying the DHT for index packets with key " + identity.getHash());
        Collection<DhtStorablePacket> packets = dht.findAll(identity.getHash(), IndexPacket.class);
        
        // build an Collection of index packets
        Collection<IndexPacket> indexPackets = new ArrayList<IndexPacket>();
        for (DhtStorablePacket packet: packets)
            if (packet instanceof IndexPacket)
                indexPackets.add((IndexPacket)packet);
            else
                log.error("DHT returned packet of class " + packet.getClass().getSimpleName() + ", expected IndexPacket.");
        
        IndexPacket mergedPacket = new IndexPacket(indexPackets);
        log.debug("Found " + mergedPacket.getDhtKeys().size() + " Email Packet keys.");
        return mergedPacket.getDhtKeys();
    }
    
    /**
     * Queries the DHT for an email packet, adds the packet to the {@link IncompleteEmailFolder},
     * and deletes the packet from the DHT.
     *
     * @author HungryHobo@mail.i2p
     */
    private class EmailPacketTask implements Runnable {
        private Hash dhtKey;
        
        /**
         * 
         * @param dhtKey The DHT key of the email packet to retrieve
         */
        public EmailPacketTask(Hash dhtKey) {
            this.dhtKey = dhtKey;
        }
        
        @Override
        public void run() {
            DhtStorablePacket packet = dht.findOne(dhtKey, EmailPacket.class);
            if (packet instanceof EmailPacket) {
                EmailPacket emailPacket = (EmailPacket)packet;
                try {
                    emailPacket.decrypt(identity);
                }
                catch (DataFormatException e) {
                    log.error("Can't decrypt email packet: " + emailPacket, e);
                    // TODO propagate error message to UI
                }
                incompleteEmailFolder.add(emailPacket);
                sendDeleteRequest(dhtKey, emailPacket.getEncryptedDeletionKey());
            }
            else
                log.error("DHT returned packet of class " + packet.getClass().getSimpleName() + ", expected EmailPacket.");
        }
        
        /**
         * Sends a delete request to the DHT.
         * @param dhtKey The DHT key of the email packet that is to be deleted
         * @param deletionKey The deletion key for the email packet
         */
        private void sendDeleteRequest(Hash dhtKey, UniqueId deletionKey) {
            // TODO
        }
   }
}