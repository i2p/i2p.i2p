package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;

import java.util.List;

import net.i2p.data.Hash;
import net.i2p.data.Payload;
import net.i2p.data.RouterIdentity;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.DataMessage;
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelMessage;
import net.i2p.router.ClientMessage;
import net.i2p.router.InNetMessage;
import net.i2p.router.MessageReceptionInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelSelectionCriteria;
import net.i2p.util.Log;

/**
 * Implement the inbound message processing logic to forward based on delivery instructions and
 * send acks.
 *
 */
class MessageHandler {
    private Log _log;
    private RouterContext _context;
    
    public MessageHandler(RouterContext ctx) {
        _context = ctx;
        _log = _context.logManager().getLog(MessageHandler.class);
    }

    public void handleMessage(DeliveryInstructions instructions, I2NPMessage message, 
                              long replyId, RouterIdentity from, Hash fromHash, 
                              long expiration, int priority, boolean sendDirect) {
        switch (instructions.getDeliveryMode()) {
            case DeliveryInstructions.DELIVERY_MODE_LOCAL:
                _log.debug("Instructions for LOCAL DELIVERY");
                if (message.getType() == DataMessage.MESSAGE_TYPE) {
                    handleLocalDestination(instructions, message, fromHash);
                } else {
                    handleLocalRouter(message, from, fromHash);
                }
                break;
            case DeliveryInstructions.DELIVERY_MODE_ROUTER:
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Instructions for ROUTER DELIVERY to " 
                               + instructions.getRouter().toBase64());
                if (_context.routerHash().equals(instructions.getRouter())) {
                    handleLocalRouter(message, from, fromHash);
                } else {
                    handleRemoteRouter(message, instructions, expiration, priority);
                }
                break;
            case DeliveryInstructions.DELIVERY_MODE_DESTINATION:
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Instructions for DESTINATION DELIVERY to " 
                               + instructions.getDestination().toBase64());
                if (_context.clientManager().isLocal(instructions.getDestination())) {
                    handleLocalDestination(instructions, message, fromHash);
                } else {
                    _log.error("Instructions requests forwarding on to a non-local destination.  Not yet supported");
                }
                break;
            case DeliveryInstructions.DELIVERY_MODE_TUNNEL:
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Instructions for TUNNEL DELIVERY to" 
                               + instructions.getTunnelId().getTunnelId() + " on " 
                               + instructions.getRouter().toBase64());
                handleTunnel(instructions, expiration, message, priority, sendDirect);
                break;
            default:
                _log.error("Message has instructions that are not yet implemented: mode = " + instructions.getDeliveryMode());
        }

    }

    private void handleLocalRouter(I2NPMessage message, RouterIdentity from, Hash fromHash) {
        _log.info("Handle " + message.getClass().getName() + " to a local router - toss it on the inbound network pool");
        InNetMessage msg = new InNetMessage(_context);
        msg.setFromRouter(from);
        msg.setFromRouterHash(fromHash);
        msg.setMessage(message);
        _context.inNetMessagePool().add(msg);
    }
    
    private void handleRemoteRouter(I2NPMessage message, DeliveryInstructions instructions, 
                                    long expiration, int priority) {
        boolean valid = _context.messageValidator().validateMessage(message.getUniqueId(), message.getMessageExpiration().getTime());
        if (!valid) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Duplicate / expired message received to remote router [" + message.getUniqueId() + " expiring on " + message.getMessageExpiration() + "]");
            _context.messageHistory().droppedOtherMessage(message);
            _context.messageHistory().messageProcessingError(message.getUniqueId(), message.getClass().getName(), "Duplicate/expired to remote router");
            return;
        }

        if (_log.shouldLog(Log.INFO))
            _log.info("Handle " + message.getClass().getName() + " to a remote router " 
                      + instructions.getRouter().toBase64() + " - fire a SendMessageDirectJob");
        int timeoutMs = (int)(expiration-_context.clock().now());
        SendMessageDirectJob j = new SendMessageDirectJob(_context, message, instructions.getRouter(), timeoutMs, priority);
        _context.jobQueue().addJob(j);
    }
    
    private void handleTunnel(DeliveryInstructions instructions, long expiration, I2NPMessage message, int priority, boolean direct) {
        Hash to = instructions.getRouter();
        long timeoutMs = expiration - _context.clock().now();
        TunnelId tunnelId = instructions.getTunnelId();
        
        if (!_context.routerHash().equals(to)) {
            // don't validate locally targetted tunnel messages, since then we'd have to tweak
            // around message validation thats already in place for SendMessageDirectJob
            boolean valid = _context.messageValidator().validateMessage(message.getUniqueId(), message.getMessageExpiration().getTime());
            if (!valid) {
                if (_log.shouldLog(Log.WARN))
                _log.warn("Duplicate / expired tunnel message received [" + message.getUniqueId() + " expiring on " + message.getMessageExpiration() + "]");
                _context.messageHistory().droppedOtherMessage(message);
                _context.messageHistory().messageProcessingError(message.getUniqueId(), 
                                                                 message.getClass().getName(), 
                                                                 "Duplicate/expired");
                return;
            }
        }

        if (direct) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Handle " + message.getClass().getName() + " to send to remote tunnel " 
                          + tunnelId.getTunnelId() + " on router " + to.toBase64());
            TunnelMessage msg = new TunnelMessage(_context);
            msg.setData(message.toByteArray());
            msg.setTunnelId(tunnelId);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Placing message of type " + message.getClass().getName() 
                           + " into the new tunnel message bound for " + tunnelId.getTunnelId() 
                           + " on " + to.toBase64());
            _context.jobQueue().addJob(new SendMessageDirectJob(_context, msg, to, (int)timeoutMs, priority));
        
            String bodyType = message.getClass().getName();
            _context.messageHistory().wrap(bodyType, message.getUniqueId(), TunnelMessage.class.getName(), msg.getUniqueId());	
        } else {
            // we received a message with instructions to send it somewhere, but we shouldn't
            // expose where we are in the process of honoring it.  so, send it out a tunnel
            TunnelId outTunnelId = selectOutboundTunnelId();
            if (outTunnelId == null) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("No outbound tunnels available to forward the message, dropping it");
                return;
            }
            _context.jobQueue().addJob(new SendTunnelMessageJob(_context, message, outTunnelId, to, tunnelId, 
                                                                null, null, null, null, timeoutMs, priority));
        }
    }
    
    private TunnelId selectOutboundTunnelId() {
        TunnelSelectionCriteria criteria = new TunnelSelectionCriteria();
        criteria.setMinimumTunnelsRequired(1);
        criteria.setMaximumTunnelsRequired(1);
        List ids = _context.tunnelManager().selectOutboundTunnelIds(criteria);
        if ( (ids == null) || (ids.size() <= 0) )
            return null;
        else
            return (TunnelId)ids.get(0);
    }
    
    private void handleLocalDestination(DeliveryInstructions instructions, I2NPMessage message, Hash fromHash) {
        boolean valid = _context.messageValidator().validateMessage(message.getUniqueId(), message.getMessageExpiration().getTime());
        if (!valid) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Duplicate / expired client message received [" + message.getUniqueId() + " expiring on " + message.getMessageExpiration() + "]");
            _context.messageHistory().droppedOtherMessage(message);
            _context.messageHistory().messageProcessingError(message.getUniqueId(), message.getClass().getName(), "Duplicate/expired client message");
            return;
        }
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handle " + message.getClass().getName() 
                       + " to a local destination - build a ClientMessage and pool it");
        ClientMessage msg = new ClientMessage();
        msg.setDestinationHash(instructions.getDestination());
        Payload payload = new Payload();
        payload.setEncryptedData(((DataMessage)message).getData());
        msg.setPayload(payload);
        MessageReceptionInfo info = new MessageReceptionInfo();
        info.setFromPeer(fromHash);
        msg.setReceptionInfo(info);
        _context.messageHistory().receivePayloadMessage(message.getUniqueId());
        _context.clientMessagePool().add(msg);
    }
}
