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
import net.i2p.data.RouterIdentity;
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.TunnelGatewayMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Unencrypt a garlic message and handle each of the cloves - locally destined
 * messages are tossed into the inbound network message pool so they're handled 
 * as if they arrived locally.  Other instructions are not yet implemented (but
 * need to be. soon)
 *
 */
class HandleGarlicMessageJob extends JobImpl implements GarlicMessageReceiver.CloveReceiver {
    private final Log _log;
    private final GarlicMessage _message;
    //private RouterIdentity _from;
    //private Hash _fromHash;
    //private Map _cloves; // map of clove Id --> Expiration of cloves we've already seen
    //private MessageHandler _handler;
    //private GarlicMessageParser _parser;
   
    private final static int FORWARD_PRIORITY = 50;
    
    /**
     *  @param from ignored
     *  @param fromHash ignored
     */
    public HandleGarlicMessageJob(RouterContext context, GarlicMessage msg, RouterIdentity from, Hash fromHash) {
        super(context);
        _log = context.logManager().getLog(HandleGarlicMessageJob.class);
        getContext().statManager().createRateStat("crypto.garlic.decryptFail", "How often garlic messages are undecryptable", "Encryption", new long[] { 5*60*1000, 60*60*1000, 24*60*60*1000 });
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("New handle garlicMessageJob called w/ message from [" + from + "]", new Exception("Debug"));
        _message = msg;
        //_from = from;
        //_fromHash = fromHash;
        //_cloves = new HashMap();
        //_handler = new MessageHandler(context);
        //_parser = new GarlicMessageParser(context);
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
                                   + instructions.getRouter().toBase64().substring(0,4));
                    SendMessageDirectJob j = new SendMessageDirectJob(getContext(), data, 
                                                                      instructions.getRouter(), 
                                                                      10*1000, 100);
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
                SendMessageDirectJob job = new SendMessageDirectJob(getContext(), gw, 
                                                                    instructions.getRouter(), 
                                                                    10*1000, 100);
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
