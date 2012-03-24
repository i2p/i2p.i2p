package net.i2p.client.datagram;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */
 
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import junit.framework.TestCase;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.crypto.DSAEngine;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;


/**
 *
 * @author Comwiz
 *
 */
public class DatagramTest extends TestCase {
    private I2PClient _client;

    public void setUp(){
    }

    protected void tearDown() {
        System.gc();
    }

    public void testDatagram() throws Exception{
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        I2PClient client = I2PClientFactory.createClient();
        Destination d = client.createDestination(out);
        I2PSession session = client.createSession(new ByteArrayInputStream(out.toByteArray()), null);

        I2PDatagramMaker dm = new I2PDatagramMaker(session);
        byte[] dg = dm.makeI2PDatagram("What's the deal with 42?".getBytes());

        I2PDatagramDissector dd = new I2PDatagramDissector();
        dd.loadI2PDatagram(dg);
        byte[] x = dd.getPayload();
        assertTrue(DataHelper.eq(x, "What's the deal with 42?".getBytes()));

        x = dd.extractPayload();
        assertTrue(DataHelper.eq(x, "What's the deal with 42?".getBytes()));

        assertEquals(d, dd.getSender());
        assertEquals(d, dd.extractSender());
    }
    
    /*public void testMakeNullDatagram() throws Exception{
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        I2PClient client = I2PClientFactory.createClient();
        Destination d = client.createDestination(out);
        I2PSession session = client.createSession(new ByteArrayInputStream(out.toByteArray()), null);
        I2PDatagramMaker dm = new I2PDatagramMaker(session);
        
        byte[] dg = dm.makeI2PDatagram(null);
        assertNull(dg);
    }*/
    
    /*public void testExtractNullDatagram() throws Exception{
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        I2PClient client = I2PClientFactory.createClient();
        Destination d = client.createDestination(out);
        I2PSession session = client.createSession(new ByteArrayInputStream(out.toByteArray()), null);
        
        I2PDatagramDissector dd = new I2PDatagramDissector();
        dd.loadI2PDatagram(null);
    }*/
    
    public void testBadagram() throws Exception{
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        I2PClient client = I2PClientFactory.createClient();
        Destination d = client.createDestination(out);
        I2PSession session = client.createSession(new ByteArrayInputStream(out.toByteArray()), null);
        DSAEngine dsaEng = DSAEngine.getInstance();

        ByteArrayOutputStream dout = new ByteArrayOutputStream();
        d.writeBytes(dout);
        dsaEng.sign(Hash.FAKE_HASH.toByteArray(), session.getPrivateKey()).writeBytes(dout);
        dout.write("blah".getBytes());

        byte[] data = dout.toByteArray();
        I2PDatagramDissector dd = new I2PDatagramDissector();
        dd.loadI2PDatagram(data);

        boolean error = false;
        try{
            dd.getPayload();
        }catch(I2PInvalidDatagramException i2pide){
            error = true;
        }
        assertTrue(error);

        error = false;
        try{
            dd.getSender();
        }catch(I2PInvalidDatagramException i2pide){
            error = true;
        }
        assertTrue(error);

        error = false;
        try{
            dd.getHash();
        }catch(I2PInvalidDatagramException i2pide){
            error = true;
        }
        assertTrue(error);
    }
}
