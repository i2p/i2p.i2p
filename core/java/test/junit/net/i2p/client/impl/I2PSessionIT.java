package net.i2p.client.impl;
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
import java.util.HashSet;
import java.util.Set;

import junit.framework.TestCase;
import net.i2p.I2PAppContext;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSessionListener;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;


/**
 *
 * @author Comwiz
 *
 */
public class I2PSessionIT extends TestCase implements I2PSessionListener {
    private Set<String> _s;
    
    
    public void setUp(){
    }
    
    protected void tearDown() {
        System.gc();
    }
    
    public void testSendClosedMessage() throws Exception{
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Destination d = I2PClientFactory.createClient().createDestination(out);
        I2PSession session = new I2PSessionImpl2(I2PAppContext.getGlobalContext(), new ByteArrayInputStream(out.toByteArray()), null);
        
        boolean error = false;
        try{
            session.sendMessage(d, out.toByteArray());
        }catch(I2PSessionException i2pse){
            error = true;
        }
        assertTrue(error);
    }
    
    public void testSendAndRecieve() throws Exception{
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Destination d = I2PClientFactory.createClient().createDestination(out);
        I2PSession session = new I2PSessionImpl2(I2PAppContext.getGlobalContext(), new ByteArrayInputStream(out.toByteArray()), null);
        session.connect();
        
        session.setSessionListener(this);
        
        _s = new HashSet<String>();
        _s.add("a");
        _s.add("b");
        _s.add("c");
        _s.add("d");
        
        session.sendMessage(d, DataHelper.getASCII("a"));
        session.sendMessage(d, DataHelper.getASCII("b"));
        session.sendMessage(d, DataHelper.getASCII("c"));
        session.sendMessage(d, DataHelper.getASCII("d"));
        
        for(int i = 0; (i < 20)&&(!_s.isEmpty()); i++){
            Thread.sleep(1000);
        }
        assertTrue(_s.isEmpty());
        
        
    }
    
    public void disconnected(I2PSession session){}
    public void errorOccurred(I2PSession session, java.lang.String message, java.lang.Throwable error){}
    public void messageAvailable(I2PSession session, int msgId, long size){
        try{
            String x = new String(session.receiveMessage(msgId));
            if(_s.contains(x))
                _s.remove(x);
        }catch(Exception e){
            fail();
        }
    }
    public void reportAbuse(I2PSession session, int severity){}
}
