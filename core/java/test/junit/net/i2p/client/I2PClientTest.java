package net.i2p.client;
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


/**
 *
 * @author Comwiz
 *
 */
public class I2PClientTest extends TestCase {
    private I2PClient _client;
    
    public void setUp(){
        _client = I2PClientFactory.createClient();
    }
    
    public void testI2PClient() throws Exception{
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        _client.createDestination(out);
        _client.createSession(new ByteArrayInputStream(out.toByteArray()), null);
    }
}