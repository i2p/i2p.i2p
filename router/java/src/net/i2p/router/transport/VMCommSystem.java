package net.i2p.router.transport;

import net.i2p.data.Hash;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageHandler;
import net.i2p.router.RouterContext;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.OutNetMessage;
import net.i2p.router.InNetMessage;
import net.i2p.router.InNetMessagePool;
import net.i2p.router.JobImpl;
import net.i2p.util.Log;

import java.io.ByteArrayInputStream;

import java.util.Map;
import java.util.HashMap;
import java.util.Collections;

/**
 * Hacked up in-VM comm system for talking between contexts.  It doesn't even
 * generate any routerAddresses, but instead tracks the peers through a singleton.
 * Currently, the comm system doesn't even inject any lag, though it could (later).
 * It does honor the standard transport stats though, but not the TCP specific ones.
 *
 */
public class VMCommSystem extends CommSystemFacade {
    private Log _log;
    private RouterContext _context;
    /**
     * Mapping from Hash to VMCommSystem for all routers hooked together
     */
    private static Map _commSystemFacades = Collections.synchronizedMap(new HashMap(16));
    
    public VMCommSystem(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(VMCommSystem.class);
        _context.statManager().createFrequencyStat("transport.sendMessageFailureFrequency", "How often do we fail to send messages?", "Transport", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("transport.sendMessageSize", "How large are the messages sent?", "Transport", new long[] { 60*1000l, 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("transport.receiveMessageSize", "How large are the messages received?", "Transport", new long[] { 60*1000l, 5*60*1000l, 60*60*1000l, 24*60*60*1000l });
        _context.statManager().createRateStat("transport.sendProcessingTime", "How long does it take from noticing that we want to send the message to having it completely sent (successfully or failed)?", "Transport", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l });
    }
    
    /**
     * The router wants us to send the given message to the peer.  Do so, or fire 
     * off the failing job.
     */
    public void processMessage(OutNetMessage msg) {
        Hash peer = msg.getTarget().getIdentity().getHash();
        VMCommSystem peerSys = (VMCommSystem)_commSystemFacades.get(peer);

        long now = _context.clock().now();
        long sendTime = now - msg.getSendBegin();

        boolean sendSuccessful = false;
        
        if (peerSys == null) {
            _context.jobQueue().addJob(msg.getOnFailedSendJob());
            _context.statManager().updateFrequency("transport.sendMessageFailureFrequency");
            _context.profileManager().messageFailed(msg.getTarget().getIdentity().getHash(), "vm");
        } else {
            _context.jobQueue().addJob(msg.getOnSendJob());
            _context.profileManager().messageSent(msg.getTarget().getIdentity().getHash(), "vm", sendTime, msg.getMessageSize());
            byte data[] = msg.getMessageData();
            _context.statManager().addRateData("transport.sendMessageSize", data.length, sendTime);
            peerSys.receive(data, _context.routerHash());
            //_context.jobQueue().addJob(new SendJob(peerSys, msg.getMessage(), _context));
            sendSuccessful = true;
        }
        
        if (true) {
            I2NPMessage dmsg = msg.getMessage();
            String type = dmsg.getClass().getName();
            _context.messageHistory().sendMessage(type, dmsg.getUniqueId(), dmsg.getMessageExpiration(), msg.getTarget().getIdentity().getHash(), sendSuccessful);
        }

        _context.statManager().addRateData("transport.sendProcessingTime", msg.getLifetime(), msg.getLifetime());
    }    
    
    private class ReceiveJob extends JobImpl {
        private Hash _from;
        private byte _msg[];
        private RouterContext _ctx;
        public ReceiveJob(Hash from, byte msg[], RouterContext us) {
            super(us);
            _ctx = us;
            _from = from;
            _msg = msg;
            // bah, ueberspeed!  
            //getTiming().setStartAfter(us.clock().now() + 50);
        }
        public void runJob() {
            I2NPMessageHandler handler = new I2NPMessageHandler(_ctx);
            try {
                I2NPMessage msg = handler.readMessage(new ByteArrayInputStream(_msg));
                int size = _msg.length;
                InNetMessage inMsg = new InNetMessage();
                inMsg.setFromRouterHash(_from);
                inMsg.setMessage(msg);
                _ctx.profileManager().messageReceived(_from, "vm", 1, size);
                _ctx.statManager().addRateData("transport.receiveMessageSize", size, 1);
                _ctx.inNetMessagePool().add(inMsg);
            } catch (Exception e) {
                _log.error("wtf, error reading/formatting a VM message?", e);
            }
        }
        public String getName() { return "Receive Message"; }
    }
    
    /**
     * We send messages between comms as bytes so that we strip any router-local
     * info.  For example, a router tags the # attempts to send through a 
     * leaseSet, what type of tunnel a tunnelId is bound to, etc.
     *
     */
    public void receive(byte message[], Hash fromPeer) {
        _context.jobQueue().addJob(new ReceiveJob(fromPeer, message, _context));
    }
    
    public void shutdown() {
        _commSystemFacades.remove(_context.routerHash());
    }
    
    public void startup() {
        _commSystemFacades.put(_context.routerHash(), this);
    }
}
