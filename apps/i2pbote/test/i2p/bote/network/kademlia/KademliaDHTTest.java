package i2p.bote.network.kademlia;

import static org.junit.Assert.fail;
import i2p.bote.network.I2PPacketDispatcher;
import i2p.bote.network.I2PSendQueue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.data.Destination;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class KademliaDHTTest {
    private static final int NUM_NODES = 100;
    
    private Collection<KademliaDHT> nodes;

    @Before
    public void setUp() throws Exception {
        File tmpDir = new File(System.getProperty("java.io.tmpdir"));
        File testDir = new File(tmpDir, "I2PBote-Test_" + System.currentTimeMillis());
        testDir.mkdir();
        
        I2PClient i2pClient = I2PClientFactory.createClient();
        
        Destination firstNode = null;
        
        nodes = Collections.synchronizedList(new ArrayList<KademliaDHT>());
        for (int i=0; i<NUM_NODES; i++) {
            ByteArrayOutputStream arrayStream = new ByteArrayOutputStream();
            Destination destination = i2pClient.createDestination(arrayStream);
            byte[] destinationArray = arrayStream.toByteArray();
            I2PSession i2pSession = i2pClient.createSession(new ByteArrayInputStream(destinationArray), null);
            
            I2PPacketDispatcher packetDispatcher = new I2PPacketDispatcher();
            i2pSession.addSessionListener(packetDispatcher, I2PSession.PROTO_ANY, I2PSession.PORT_ANY);
            
            I2PSendQueue sendQueue = new I2PSendQueue(i2pSession, packetDispatcher);
            
            File peerFile = new File(testDir, "peers" + i);
            if (firstNode != null) {
                FileWriter writer = new FileWriter(peerFile);
                writer.write(firstNode.toBase64());
            }
            else
                firstNode = destination;
            
            nodes.add(new KademliaDHT(destination, sendQueue, packetDispatcher, peerFile));
        }
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void testBootstrap() {
        fail("Not yet implemented");
    }
    
    @Test
    public void testFindOne() {
        fail("Not yet implemented");
    }

    @Test
    public void testFindAll() {
        fail("Not yet implemented");
    }

    @Test
    public void testStore() {
        fail("Not yet implemented");
    }
}