package i2p.bote.packet.kademlia;


import static junit.framework.Assert.assertTrue;

import i2p.bote.packet.dht.FindClosePeersPacket;

import java.util.Arrays;

import net.i2p.data.Hash;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class FindCloseNodesPacketTest {
    FindClosePeersPacket findCloseNodesPacket;

    @Before
    public void setUp() throws Exception {
        Hash key = new Hash(new byte[] {-48, 78, 66, 58, -79, 87, 38, -103, -60, -27, 108, 55, 117, 37, -99, 93, -23, -102, -83, 20, 44, -80, 65, 89, -68, -73, 69, 51, 115, 79, 24, 127});
        
        findCloseNodesPacket = new FindClosePeersPacket(key);
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void toByteArrayAndBack() throws Exception {
        byte[] arrayA = findCloseNodesPacket.toByteArray();
        byte[] arrayB = new FindClosePeersPacket(arrayA).toByteArray();
        assertTrue("The two arrays differ!", Arrays.equals(arrayA, arrayB));
    }
}