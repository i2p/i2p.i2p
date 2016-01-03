package net.i2p.client.streaming.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.util.Log;

import junit.framework.TestCase;

abstract class StreamingITBase extends TestCase {

    // TODO: this may need to start a full router
    
    protected abstract Properties getProperties();
    
    protected I2PSession createSession() throws Exception {
        I2PClient client = I2PClientFactory.createClient();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
        client.createDestination(baos);
        Properties p = getProperties();

        I2PSession sess = client.createSession(new ByteArrayInputStream(baos.toByteArray()), p);
        sess.connect();
        return sess;
    }
    
    protected abstract Runnable getClient(I2PAppContext ctx, I2PSession session);
    
    protected final Thread runClient(I2PAppContext ctx, I2PSession session) {
        Thread t = new Thread(getClient(ctx,session));
        t.setName("client");
        t.setDaemon(true);
        t.start();
        return t;
    }
    
    protected abstract class RunnerBase implements Runnable {
        
        protected final I2PAppContext _context;
        protected final I2PSession _session;
        protected final Log _log;
        
        protected RunnerBase(I2PAppContext ctx, I2PSession session) {
            _context = ctx;
            _session = session;
            _log = ctx.logManager().getLog(this.getClass());
        }
    }
    
    protected abstract Runnable getServer(I2PAppContext ctx, I2PSession session);
   
    protected final Thread runServer(I2PAppContext ctx, I2PSession session) {
        Thread t = new Thread(getServer(ctx,session));
        t.setName("servert");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
