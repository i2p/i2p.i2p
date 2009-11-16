package i2p.bote;

import i2p.bote.packet.EmailPacketTest;
import i2p.bote.packet.I2PBotePacketTest;
import i2p.bote.packet.ResponsePacketTest;
import i2p.bote.packet.kademlia.FindCloseNodesPacketTest;
import i2p.bote.packet.kademlia.StorageRequestTest;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

import junit.framework.Test;
import junit.framework.TestSuite;

@RunWith(Suite.class)
@Suite.SuiteClasses( { I2PBotePacketTest.class, StorageRequestTest.class, EmailPacketTest.class, FindCloseNodesPacketTest.class, ResponsePacketTest.class })
public class AllTests {

    public static Test suite() {
        TestSuite suite = new TestSuite("Test for i2p.bote");
        //$JUnit-BEGIN$

        //$JUnit-END$
        return suite;
    }
}