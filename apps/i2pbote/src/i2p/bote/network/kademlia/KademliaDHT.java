package i2p.bote.network.kademlia;

import i2p.bote.network.DHT;
import i2p.bote.network.I2PPacketDispatcher;
import i2p.bote.network.I2PSendQueue;
import i2p.bote.network.PacketBatch;
import i2p.bote.network.PacketListener;
import i2p.bote.network.DhtStorageHandler;
import i2p.bote.packet.CommunicationPacket;
import i2p.bote.packet.I2PBotePacket;
import i2p.bote.packet.PeerList;
import i2p.bote.packet.ResponsePacket;
import i2p.bote.packet.StatusCode;
import i2p.bote.packet.dht.DhtStorablePacket;
import i2p.bote.packet.dht.FindClosePeersPacket;
import i2p.bote.packet.dht.RetrieveRequest;
import i2p.bote.packet.dht.StoreRequest;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.nettgryppa.security.HashCash;

import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;

/**
 * The main class of the Kademlia implementation. All the high-level Kademlia logic
 * is in here.
 * 
 * Resources used:
 *   [1] http://pdos.csail.mit.edu/~petar/papers/maymounkov-kademlia-lncs.pdf
 *   [2] http://xlattice.sourceforge.net/components/protocol/kademlia/specs.html
 *   [3] http://en.wikipedia.org/wiki/Kademlia
 *   [4] http://www.barsoom.org/papers/infocom-2006-kad.pdf
 *   [5] http://doc.tm.uka.de/SKademlia_2007.pdf
 *   [6] OverSim (http://www.oversim.org/), which includes a S/Kademlia implementation
 *   
 * @author HungryHobo@mail.i2p
 */
public class KademliaDHT implements DHT, PacketListener {
    private Log log = new Log(KademliaDHT.class);
    private Hash localDestinationHash;
    private I2PSendQueue sendQueue;
    private I2PPacketDispatcher i2pReceiver;
    private File peerFile;
    private Collection<KademliaPeer> initialPeers;
    private BucketManager bucketManager;
    private Map<Class<? extends DhtStorablePacket>, DhtStorageHandler> storageHandlers;

    public KademliaDHT(Destination localDestination, I2PSendQueue sendQueue, I2PPacketDispatcher i2pReceiver, File peerFile) {
        localDestinationHash = localDestination.calculateHash();
        this.sendQueue = sendQueue;
        this.i2pReceiver = i2pReceiver;
        this.peerFile = peerFile;
        initialPeers = readPeersFromFile(peerFile);
        bucketManager = new BucketManager(sendQueue, initialPeers, localDestination.calculateHash());
        storageHandlers = new ConcurrentHashMap<Class<? extends DhtStorablePacket>, DhtStorageHandler>();
    }
    
    /**
     * Returns the S nodes closest to a given key by querying peers.
     * This method blocks. It returns after <code>CLOSEST_NODES_LOOKUP_TIMEOUT+1</code> seconds at
     * the longest.
     *
     * The number of pending requests never exceeds ALPHA. According to [4], this is the most efficient.
     * 
     * If there are less than <code>s</code> results after the kademlia lookup finishes, nodes from
     * the sibling list are used.
     */
    private Collection<Destination> getClosestNodes(Hash key) {
        ClosestNodesLookupTask lookupTask = new ClosestNodesLookupTask(key, sendQueue, i2pReceiver, bucketManager);
        lookupTask.run();
        return lookupTask.getResults();
    }

    @Override
    public DhtStorablePacket findOne(Hash key, Class<? extends DhtStorablePacket> dataType) {
        Collection<DhtStorablePacket> results = find(key, dataType, false);
        if (results.isEmpty())
            return null;
        else
            return results.iterator().next();
    }

    @Override
    public Collection<DhtStorablePacket> findAll(Hash key, Class<? extends DhtStorablePacket> dataType) {
        return find(key, dataType, true);
    }

    @Override
    public void setStorageHandler(Class<? extends DhtStorablePacket> packetType, DhtStorageHandler storageHandler) {
        storageHandlers.put(packetType, storageHandler);
    }
    
    @Override
    public int getNumPeers() {
        return bucketManager.getPeerCount();
    }
    
    private Collection<DhtStorablePacket> find(Hash key, Class<? extends DhtStorablePacket> dataType, boolean exhaustive) {
        final Collection<Destination> closeNodes = getClosestNodes(key);
        log.debug("Querying " + closeNodes.size() + " nodes with Kademlia key " + key);
        
        final Collection<I2PBotePacket> receivedPackets = new ConcurrentHashSet<I2PBotePacket>();   // avoid adding duplicate packets
        
        PacketListener packetListener = new PacketListener() {
            @Override
            public void packetReceived(CommunicationPacket packet, Destination sender, long receiveTime) {
                // add packet to list of received packets if the packet is in response to a RetrieveRequest
                if (packet instanceof RetrieveRequest && closeNodes.contains(sender))
                    receivedPackets.add(packet);
            }
        };
        i2pReceiver.addPacketListener(packetListener);
        
        // Send the retrieve requests
        PacketBatch batch = new PacketBatch();
        for (Destination node: closeNodes)
            batch.putPacket(new RetrieveRequest(key, dataType), node);
        sendQueue.send(batch);
        try {
            batch.awaitSendCompletion();
        }
        catch (InterruptedException e) {
            log.warn("Interrupted while waiting for Retrieve Requests to be sent.", e);
        }

        // wait for replies
        try {
            if (exhaustive)
                TimeUnit.SECONDS.sleep(60);
            else
                batch.awaitFirstReply(30, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            log.warn("Interrupted while waiting for responses to Retrieve Requests.", e);
        }
        log.debug(batch.getResponsePackets().size() + " response packets received for hash " + key + " and data type " + dataType);
        
        sendQueue.remove(batch);
        i2pReceiver.removePacketListener(packetListener);
        
        ConcurrentHashSet<DhtStorablePacket> storablePackets = getStorablePackets(batch);
        DhtStorablePacket localResult = findLocally(key, dataType);
        if (localResult != null)
            storablePackets.add(localResult);
        return storablePackets;
    }

    private DhtStorablePacket findLocally(Hash key, Class<? extends DhtStorablePacket> dataType) {
        DhtStorageHandler storageHandler = storageHandlers.get(dataType);
        if (storageHandler != null)
            return storageHandler.retrieve(key);
        else
            return null;
    }
    
    /**
     * Returns all <code>DhtStorablePacket</code> packets that have been received as a response to a send batch.
     * @param batch
     * @return
     */
    private ConcurrentHashSet<DhtStorablePacket> getStorablePackets(PacketBatch batch) {
        ConcurrentHashSet<DhtStorablePacket> storablePackets = new ConcurrentHashSet<DhtStorablePacket>();
        for (I2PBotePacket packet: batch.getResponsePackets())
            if (packet instanceof DhtStorablePacket)
                storablePackets.add((DhtStorablePacket)packet);
        return storablePackets;
    }
    
    @Override
    public void store(DhtStorablePacket packet) throws NoSuchAlgorithmException {
        Hash key = packet.getDhtKey();
        
        Collection<Destination> closeNodes = getClosestNodes(key);
        log.debug("Storing a " + packet.getClass().getSimpleName() + " with key " + key + " on " + closeNodes.size() + " nodes");
        
        HashCash hashCash = HashCash.mintCash("", 1);   // TODO
        StoreRequest storageRequest = new StoreRequest(hashCash, packet);
        PacketBatch batch = new PacketBatch();
        for (Destination node: closeNodes)
            batch.putPacket(storageRequest, node);
        sendQueue.send(batch);
        
        try {
            batch.awaitSendCompletion();
        }
        catch (InterruptedException e) {
            log.warn("Interrupted while waiting for responses to Storage Requests to be sent.", e);
        }
        
        sendQueue.remove(batch);
    }

    @Override
    public void start() {
        i2pReceiver.addPacketListener(this);
        bucketManager.start();
        bootstrap();
    }
    
    @Override
    public void shutDown() {
        i2pReceiver.removePacketListener(this);
        bucketManager.requestShutdown();
        writePeersToFile(peerFile);
    }
    
    private void bootstrap() {
        new BootstrapTask().start();
    }
    
    private class BootstrapTask extends Thread {
        public BootstrapTask() {
            setDaemon(true);
        }
        
        @Override
        public void run() {
            log.debug("Bootstrap start");
            while (true) {
                for (KademliaPeer bootstrapNode: initialPeers) {
                    bootstrapNode.setLastReception(-1);
                    bucketManager.addOrUpdate(bootstrapNode);
                    Collection<Destination> closestNodes = getClosestNodes(localDestinationHash);
                    // if last reception time is not set, the node didn't respond, so remove it
                    if (bootstrapNode.getLastReception() <= 0)
                        bucketManager.remove(bootstrapNode);
                    
                    if (closestNodes.isEmpty()) {
                        log.debug("No response from bootstrap node " + bootstrapNode);
                        bucketManager.remove(bootstrapNode);
                    }
                    else {
                        bucketManager.refreshAll();
                        log.info("Bootstrapping finished. Number of peers = " + bucketManager.getPeerCount());
                        return;
                    }
                }
                
                log.warn("Can't bootstrap off any known peer, will retry shortly.");
                try {
                    TimeUnit.MINUTES.sleep(1);
                } catch (InterruptedException e) {
                    log.error("Interrupted while pausing after unsuccessful bootstrap attempt.", e);
                }
            }
        }
    }
    
    private void writePeersToFile(File peerFile) {
        // TODO
    }
    
    private Collection<KademliaPeer> readPeersFromFile(File peerFile) {
        log.info("Reading peers from file: '" + peerFile.getAbsolutePath() + "'");
        Collection<KademliaPeer> peers = new ArrayList<KademliaPeer>();

        FileInputStream fileInput = null;
        try {
            fileInput = new FileInputStream(peerFile);
        }
        catch (FileNotFoundException notFoundExc) {
            log.error("Peer file not found, creating a new one: '" + peerFile.getAbsolutePath() + "'");
            log.error("Please provide a peer file with at least one active peer and restart the application.");
            try {
                createPeerFile(peerFile);
            }
            catch (IOException ioExc) {
                log.error("Can't create peer file.", ioExc);
                return peers;
            }
        }
        
        BufferedReader inputBuffer = new BufferedReader(new InputStreamReader(fileInput));
        
        while (true) {
            String line = null;
            try {
                line = inputBuffer.readLine();
            }
            catch (IOException e) {
                log.error("Error reading peer file.", e);
            }
            if (line == null)
                break;
            
            if (!line.startsWith("#"))
                // TODO read "up since" time if present, use a separate method for parsing lines
                // TODO write "up since" time back to the peer file
                try {
                	Destination destination = new Destination(line);
                	KademliaPeer peer = new KademliaPeer(destination, 0);
                	
                    // don't add the local destination as a peer
                    if (!peer.getDestinationHash().equals(localDestinationHash))
                        peers.add(peer);
                }
                catch (DataFormatException e) {
                    log.error("Invalid destination key in '" + peerFile + "': " + line, e);
                }
        }
        
        return peers;
    }
    
    private void createPeerFile(File file) throws IOException {
        String lineFeed = System.getProperty("line.separator");
        
        file.createNewFile();
        FileWriter fileWriter = new FileWriter(file);
        fileWriter.write("# Each line in this file should begin with a 516-byte I2P destination key in" + lineFeed);
        fileWriter.write("# Base64 format. Optionally, the destination key can be followed by a space" + lineFeed);
        fileWriter.write("# and an \"active since\" time." + lineFeed);
        fileWriter.write("# Lines beginning with a # are ignored." + lineFeed);
        fileWriter.close();
    }

    private void sendPeerList(FindClosePeersPacket packet, Destination destination) {
        Collection<KademliaPeer> closestPeers = bucketManager.getClosestPeers(packet.getKey(), KademliaConstants.K);
        PeerList peerList = new PeerList(closestPeers);
        sendQueue.sendResponse(peerList, destination, packet.getPacketId());
    }

    // PacketListener implementation
    @Override
    public void packetReceived(CommunicationPacket packet, Destination sender, long receiveTime) {
        if (packet instanceof FindClosePeersPacket)
            sendPeerList((FindClosePeersPacket)packet, sender);
        else if (packet instanceof StoreRequest) {
            DhtStorablePacket packetToStore = ((StoreRequest)packet).getPacketToStore();
            if (packetToStore != null) {
                DhtStorageHandler storageHandler = storageHandlers.get(packetToStore.getClass());
                if (storageHandler != null)
                    storageHandler.store(packetToStore);
                else
                    log.warn("No storage handler found for type " + packetToStore.getClass().getSimpleName() + ".");
            }
        }
        else if (packet instanceof RetrieveRequest) {
            RetrieveRequest retrieveRequest = (RetrieveRequest)packet;
            DhtStorageHandler storageHandler = storageHandlers.get(retrieveRequest.getDataType());
            if (storageHandler != null) {
                DhtStorablePacket storedPacket = storageHandler.retrieve(retrieveRequest.getKey());
                // if requested packet found, send it to the requester
                if (storedPacket != null) {
                    log.debug("Packet found for retrieve request: [" + retrieveRequest + "], replying to sender: [" + sender + "]");
                    ResponsePacket response = new ResponsePacket(storedPacket, StatusCode.OK, retrieveRequest.getPacketId());
                    sendQueue.send(response, sender);
                }
                else
                    log.debug("No matching packet found for retrieve request: [" + retrieveRequest + "]");
            }
            else
                log.warn("No storage handler found for type " + packet.getClass().getSimpleName() + ".");
        }
        
        // bucketManager is not registered as a PacketListener, so notify it here
        bucketManager.packetReceived(packet, sender, receiveTime);
    }
}