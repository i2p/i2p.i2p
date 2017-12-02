package net.i2p.router;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.data.i2np.I2NPMessageImpl;

/**
 * Demo of a stripped down router - no tunnels, no netDb, no i2cp, no peer profiling,
 * just the SSU comm layer, crypto, and associated infrastructure, extended to handle
 * a new type of message ("FooMessage").
 *
 */
public class SSUDemo {
    RouterContext _us;

    public static void main(String args[]) {
        boolean testNTCP = args.length > 0 && args[0].equals("ntcp");
        SSUDemo demo = new SSUDemo();
        demo.run(testNTCP);
    }
    
    public SSUDemo() {}

    public void run(boolean testNTCP) {
        String cfgFile = "router.config";
        Properties envProps = getEnv(testNTCP);
        Router r = new Router(cfgFile, envProps);
        r.runRouter();
        _us = r.getContext();
        setupHandlers();
        // wait for it to warm up a bit
        try { Thread.sleep(30*1000); } catch (InterruptedException ie) {}
        // now write out our ident and info
        RouterInfo myInfo = _us.router().getRouterInfo();
        storeMyInfo(myInfo);
        // look for any other peers written to the same directory, and send each
        // a single Foo message (0x0123), unless they've already contacted us first.
        // this call never returns
        loadPeers();
    }
    
    private static Properties getEnv(boolean testNTCP) {
        Properties envProps = new Properties();
        // disable one of the transports and UPnP
        if (testNTCP)
            envProps.setProperty("i2np.udp.enable", "false");
        else
            envProps.setProperty("i2np.ntcp.enable", "false");
        envProps.setProperty("i2np.upnp.enable", "false");
        // we want SNTP synchronization for replay prevention
        envProps.setProperty("time.disabled", "false");
        // allow 127.0.0.1/10.0.0.1/etc (useful for testing).  If this is false,
        // peers who say they're on an invalid IP are banlisted
        envProps.setProperty("i2np.allowLocal", "true");
        // IPv6
        envProps.setProperty("i2np.udp.ipv6", "enable");
        envProps.setProperty("i2np.ntcp.ipv6", "enable");
        // explicit IP+port.  at least one router on the net has to have their IP+port
        // set, since there has to be someone to detect one's IP off.  most don't need
        // to set these though
        //envProps.setProperty("i2np.udp.host", "127.0.0.1");
        envProps.setProperty("i2np.udp.host", "::1");
        envProps.setProperty("i2np.ntcp.autoip", "false");
        envProps.setProperty("i2np.ntcp.hostname", "::1");
        // we don't have a context yet to use its random
        String port = Integer.toString(44000 + (((int) System.currentTimeMillis()) & (16384 - 1)));
        envProps.setProperty("i2np.udp.internalPort", port);
        envProps.setProperty("i2np.udp.port", port);
        envProps.setProperty("i2np.ntcp.autoport", "false");
        envProps.setProperty("i2np.ntcp.port", port);
        // disable I2CP, the netDb, peer testing/profile persistence, and tunnel
        // creation/management
        envProps.setProperty("i2p.dummyClientFacade", "true");
        envProps.setProperty("i2p.dummyNetDb", "true");
        envProps.setProperty("i2p.dummyPeerManager", "true");
        envProps.setProperty("i2p.dummyTunnelManager", "true");
        // set to false if you want to use HMAC-SHA256-128 instead of HMAC-MD5-128 as
        // the SSU MAC
        //envProps.setProperty("i2p.HMACMD5", "true");
        // if you're using the HMAC MD5, by default it will use a 32 byte MAC field,
        // which is a bug, as it doesn't generate the same values as a 16 byte MAC field.
        // set this to false if you don't want the bug
        //envProps.setProperty("i2p.HMACBrokenSize", "false");
        // no need to include any stats in the routerInfo we send to people on SSU
        // session establishment
        envProps.setProperty("router.publishPeerRankings", "false");
        // write the logs to ./logs/log-router-*.txt (logger configured with the file
        // ./logger.config, or another config file specified as
        // -Dlogger.configLocation=blah)
        // avoid conflicts over log
        envProps.setProperty("loggerFilenameOverride", "logs/log-router-" + port + "-@.txt");
        System.setProperty("wrapper.logfile", "wrapper-" + port + ".log");
        // avoid conflicts over key backup etc. so we don't all use the same keys
        envProps.setProperty("router.keyBackupDir", "keyBackup/router-" + port);
        envProps.setProperty("router.info.location", "router-" + port + ".info");
        envProps.setProperty("router.keys.location", "router-" + port + ".keys");
        envProps.setProperty("router.configLocation", "router-" + port + ".config");
        envProps.setProperty("router.pingFile", "router-" + port + ".ping");
        // avoid conflicts over blockfile
        envProps.setProperty("i2p.naming.impl", "net.i2p.client.naming.HostsTxtNamingService");
        return envProps;
    }
    
    private void setupHandlers() {
        // netDb store is sent on connection establishment, which includes contact info
        // for the peer.  the DBStoreJobBuilder builds a new asynchronous Job to process
        // each one received (storing it in our in-memory, passive netDb)
        _us.inNetMessagePool().registerHandlerJobBuilder(DatabaseStoreMessage.MESSAGE_TYPE, new DBStoreJobBuilder());
        // handle any Foo messages by displaying them on stdout
        _us.inNetMessagePool().registerHandlerJobBuilder(FooMessage.MESSAGE_TYPE, new FooJobBuilder());
    }
    
    /** random place for storing router info files - written as $dir/base64(SHA256(info.getIdentity)) */
    private static File getInfoDir() { return new File("/tmp/ssuDemoInfo/"); }
    
    private static void storeMyInfo(RouterInfo info) {
        File infoDir = getInfoDir();
        if (!infoDir.exists())
            infoDir.mkdirs();
        FileOutputStream fos = null;
        File infoFile = new File(infoDir, info.getIdentity().calculateHash().toBase64());
        infoFile.deleteOnExit();
        try {
            fos = new FileOutputStream(infoFile);
            info.writeBytes(fos);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        } catch (DataFormatException dfe) {
            dfe.printStackTrace();
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
        }

        System.out.println("Our info stored at: " + infoFile.getAbsolutePath());
    }
    
    private void loadPeers() {
        File infoDir = getInfoDir();
        if (!infoDir.exists())
            infoDir.mkdirs();
        while (true) {
            File peerFiles[] = infoDir.listFiles();
            if ( (peerFiles != null) && (peerFiles.length > 0) ) {
                for (int i = 0; i < peerFiles.length; i++) {
                    if (peerFiles[i].isFile() && !peerFiles[i].isHidden()) {
                        if (!_us.routerHash().toBase64().equals(peerFiles[i].getName())) {
                            System.out.println("Reading info: " + peerFiles[i].getAbsolutePath());
                            try {
                                FileInputStream in = new FileInputStream(peerFiles[i]);
                                RouterInfo ri = new RouterInfo();
                                ri.readBytes(in);
                                peerRead(ri);
                            } catch (IOException ioe) {
                                System.err.println("Error reading " + peerFiles[i].getAbsolutePath());
                                ioe.printStackTrace();
                            } catch (DataFormatException dfe) {
                                System.err.println("Corrupt " + peerFiles[i].getAbsolutePath());
                                dfe.printStackTrace();
                            }
                        }
                    }
                }
            }
            try { Thread.sleep(30*1000); } catch (InterruptedException ie) {}
        }
    }
    
    private void peerRead(RouterInfo ri) {
        RouterInfo old = _us.netDb().store(ri.getIdentity().calculateHash(), ri);
        if (old == null)
            newPeerRead(ri);
    }
    
    private void newPeerRead(RouterInfo ri) {
        FooMessage data = new FooMessage(_us, new byte[] { 0x0, 0x1, 0x2, 0x3 });
        // _us.clock() is an ntp synchronized clock.  give up on sending this message
        // if it doesn't get ACKed within the next 10 seconds
        OutNetMessage out = new OutNetMessage(_us, data, _us.clock().now() + 10*1000, 100, ri);
        System.out.println("SEND: " + Base64.encode(data.getData()) + " to " +
                           ri.getIdentity().calculateHash());
        // job fired if we can't contact them, or if it takes too long to get an ACK
        out.setOnFailedSendJob(null); 
        // job fired once the transport gets a full ACK of the message
        out.setOnSendJob(new AfterACK());
        // queue up the message, establishing a new SSU session if necessary, using
        // their direct SSU address if they have one, or their indirect SSU addresses
        // if they don't.  If we cannot contact them, we will 'banlist' their address,
        // during which time we will not even attempt to send messages to them.  We also
        // drop their netDb info when we banlist them, in case their info is no longer
        // correct.  Since the netDb is disabled for all meaningful purposes, the SSUDemo
        // will be responsible for fetching such information.
        _us.outNetMessagePool().add(out);
    }
 
    /** fired if and only if the FooMessage is ACKed before we time out */
    private class AfterACK extends JobImpl {
        public AfterACK() { super(_us); }
        public void runJob() { System.out.println("Foo message sent completely"); }
        public String getName() { return "After Foo message send"; }
    }
    
    ////
    // Foo and netDb store handling below
    
    /**
     * Deal with an Foo message received
     */
    private class FooJobBuilder implements HandlerJobBuilder {
        @SuppressWarnings("deprecation")
        public FooJobBuilder() {
            I2NPMessageImpl.registerBuilder(new FooBuilder(), FooMessage.MESSAGE_TYPE);
        }

        public Job createJob(I2NPMessage receivedMessage, RouterIdentity from, Hash fromHash) {
            return new FooHandleJob(_us, receivedMessage, from, fromHash);
        }
    }

    private static class FooHandleJob extends JobImpl {
        private final I2NPMessage _msg;
        private final Hash _from;

        public FooHandleJob(RouterContext ctx, I2NPMessage receivedMessage, RouterIdentity from, Hash fromHash) {
            super(ctx);
            _msg = receivedMessage;
            _from = fromHash;
        }

        public void runJob() {
            // we know its a FooMessage, since thats the type of message that the handler
            // is registered as
            FooMessage m = (FooMessage)_msg;
            System.out.println("RECV FooMessage: " + Base64.encode(m.getData()) + " from " + _from);
        }
        public String getName() { return "Handle Foo message"; }
    }

    private static class FooBuilder implements I2NPMessageImpl.Builder {
        public I2NPMessage build(I2PAppContext ctx) { return new FooMessage(ctx, null); }
    }
    
    /**
     * Just carry some data...
     */
    private static class FooMessage extends I2NPMessageImpl {
        private byte[] _data;
        public static final int MESSAGE_TYPE = 17;

        public FooMessage(I2PAppContext ctx, byte data[]) {
            super(ctx);
            _data = data;
        }

        /** pull the read data off */
        public byte[] getData() { return _data; }

        /** specify the payload to be sent */
        public void setData(byte data[]) { _data = data; }
        
        public int getType() { return MESSAGE_TYPE; }

        protected int calculateWrittenLength() { return _data.length; }

        public void readMessage(byte[] data, int offset, int dataSize, int type) throws I2NPMessageException {
            _data = new byte[dataSize];
            System.arraycopy(data, offset, _data, 0, dataSize);
        }

        protected int writeMessageBody(byte[] out, int curIndex) throws I2NPMessageException {
            System.arraycopy(_data, 0, out, curIndex, _data.length);
            return curIndex + _data.length;
        }
    }
    
    ////
    // netDb store handling below
    
    /**
     * Handle any netDb stores from the peer - they send us their netDb as part of
     * their SSU establishment (and we send them ours).
     */
    private class DBStoreJobBuilder implements HandlerJobBuilder {
        public Job createJob(I2NPMessage receivedMessage, RouterIdentity from, Hash fromHash) {
            return new HandleJob(_us, receivedMessage, from, fromHash);
        }
    }

    private class HandleJob extends JobImpl {
        private final I2NPMessage _msg;

        public HandleJob(RouterContext ctx, I2NPMessage receivedMessage, RouterIdentity from, Hash fromHash) {
            super(ctx);
            _msg = receivedMessage;
        }

        public void runJob() {
            // we know its a DatabaseStoreMessage, since thats the type of message that the handler
            // is registered as
            DatabaseStoreMessage m = (DatabaseStoreMessage)_msg;
            System.out.println("RECV: " + m);
            try {
                _us.netDb().store(m.getKey(), (RouterInfo) m.getEntry());
            } catch (IllegalArgumentException iae) {
                iae.printStackTrace();
            }
        }

        public String getName() { return "Handle netDb store"; }
    }
}
