package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Hash;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelGatewayMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Unencrypt a garlic message and handle each of the cloves - locally destined
 * messages are tossed into the inbound network message pool so they're handled 
 * as if they arrived locally.  Other instructions are not yet implemented (but
 * need to be. soon)
 *
 * This is the handler for garlic message not received down a tunnel, which is the
 * case for floodfills receiving netdb messages.
 * It is not the handler for garlic messages received down a tunnel,
 * as InNetMessagePool short circuits tunnel messages,
 * and those garlic messages are handled in InboundMessageDistributor.
 *
 * Public for JobQueue as these jobs may be dropped.
 */
public class HandleGarlicMessageJob extends JobImpl implements GarlicMessageReceiver.CloveReceiver {
    private final Log _log;
    private final GarlicMessage _message;
    //private RouterIdentity _from;
    //private Hash _fromHash;
    //private Map _cloves; // map of clove Id --> Expiration of cloves we've already seen
    //private MessageHandler _handler;
    //private GarlicMessageParser _parser;
   
    private final static int ROUTER_PRIORITY = OutNetMessage.PRIORITY_LOWEST;
    private final static int TUNNEL_PRIORITY = OutNetMessage.PRIORITY_LOWEST;

    /**
     *  @param from ignored
     *  @param fromHash ignored
     */
    public HandleGarlicMessageJob(RouterContext context, GarlicMessage msg, RouterIdentity from, Hash fromHash) {
        super(context);
        _log = context.logManager().getLog(HandleGarlicMessageJob.class);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Garlic Message not down a tunnel from [" + from + "]");
        _message = msg;
        //_from = from;
        //_fromHash = fromHash;
        //_cloves = new HashMap();
        //_handler = new MessageHandler(context);
        //_parser = new GarlicMessageParser(context);
        // all createRateStat in OCMOSJ.init()
    }
    
    public String getName() { return "Handle Inbound Garlic Message"; }
    public void runJob() {
        GarlicMessageReceiver recv = new GarlicMessageReceiver(getContext(), this);
        recv.receive(_message);
    }
    
    public void handleClove(DeliveryInstructions instructions, I2NPMessage data) {
        switch (instructions.getDeliveryMode()) {
            case DeliveryInstructions.DELIVERY_MODE_LOCAL:
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("local delivery instructions for clove: " + data);
                getContext().inNetMessagePool().add(data, null, null);
                return;
            case DeliveryInstructions.DELIVERY_MODE_DESTINATION:
                if (_log.shouldLog(Log.ERROR))
                    _log.error("this message didn't come down a tunnel, not forwarding to a destination: " 
                               + instructions + " - " + data);
                return;
            case DeliveryInstructions.DELIVERY_MODE_ROUTER:
                if (getContext().routerHash().equals(instructions.getRouter())) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("router delivery instructions targetting us");
                    getContext().inNetMessagePool().add(data, null, null);
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("router delivery instructions targetting " 
                                   + instructions.getRouter().toBase64().substring(0,4) + " for " + data);
                    SendMessageDirectJob j = new SendMessageDirectJob(getContext(), data, 
                                                                      instructions.getRouter(), 
                                                                      10*1000, ROUTER_PRIORITY);
                    // run it inline (adds to the outNetPool if it has the router info, otherwise queue a lookup)
                    j.runJob(); 
                    //getContext().jobQueue().addJob(j);
                }
                return;
            case DeliveryInstructions.DELIVERY_MODE_TUNNEL:
                TunnelGatewayMessage gw = new TunnelGatewayMessage(getContext());
                gw.setMessage(data);
                gw.setTunnelId(instructions.getTunnelId());
                gw.setMessageExpiration(data.getMessageExpiration());
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("tunnel delivery instructions targetting " 
                               + instructions.getRouter().toBase64().substring(0,4) + " for " + data);
                SendMessageDirectJob job = new SendMessageDirectJob(getContext(), gw, 
                                                                    instructions.getRouter(), 
                                                                    10*1000, TUNNEL_PRIORITY);
                // run it inline (adds to the outNetPool if it has the router info, otherwise queue a lookup)
                job.runJob(); 
                // getContext().jobQueue().addJob(job);
                return;
            default:
                _log.error("Unknown instruction " + instructions.getDeliveryMode() + ": " + instructions);
                return;
        }
    }
    
    @Override
    public void dropped() {
        getContext().messageHistory().messageProcessingError(_message.getUniqueId(), 
                                                         _message.getClass().getName(), 
                                                         "Dropped due to overload");
    }
}
