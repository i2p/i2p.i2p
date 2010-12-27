package net.i2p.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.I2PAppContext;
import net.i2p.data.i2cp.BandwidthLimitsMessage;
import net.i2p.data.i2cp.DestReplyMessage;
import net.i2p.data.i2cp.DisconnectMessage;
import net.i2p.data.i2cp.MessagePayloadMessage;
import net.i2p.data.i2cp.MessageStatusMessage;
import net.i2p.data.i2cp.RequestLeaseSetMessage;
import net.i2p.data.i2cp.SessionStatusMessage;
import net.i2p.data.i2cp.SetDateMessage;

/**
 * Contains a map of message handlers that a session will want to use
 *
 * @author jrandom
 */
class I2PClientMessageHandlerMap {
    /** map of message type id --> I2CPMessageHandler */
    protected I2CPMessageHandler _handlers[];

    /** for extension */
    public I2PClientMessageHandlerMap() {}

    public I2PClientMessageHandlerMap(I2PAppContext context) {
        int highest = DisconnectMessage.MESSAGE_TYPE;
        highest = Math.max(highest, SessionStatusMessage.MESSAGE_TYPE);
        highest = Math.max(highest, RequestLeaseSetMessage.MESSAGE_TYPE);
        highest = Math.max(highest, MessagePayloadMessage.MESSAGE_TYPE);
        highest = Math.max(highest, MessageStatusMessage.MESSAGE_TYPE);
        highest = Math.max(highest, SetDateMessage.MESSAGE_TYPE);
        highest = Math.max(highest, DestReplyMessage.MESSAGE_TYPE);
        highest = Math.max(highest, BandwidthLimitsMessage.MESSAGE_TYPE);
        
        _handlers = new I2CPMessageHandler[highest+1];
        _handlers[DisconnectMessage.MESSAGE_TYPE] = new DisconnectMessageHandler(context);
        _handlers[SessionStatusMessage.MESSAGE_TYPE] = new SessionStatusMessageHandler(context);
        _handlers[RequestLeaseSetMessage.MESSAGE_TYPE] = new RequestLeaseSetMessageHandler(context);
        _handlers[MessagePayloadMessage.MESSAGE_TYPE] = new MessagePayloadMessageHandler(context);
        _handlers[MessageStatusMessage.MESSAGE_TYPE] = new MessageStatusMessageHandler(context);
        _handlers[SetDateMessage.MESSAGE_TYPE] = new SetDateMessageHandler(context);
        _handlers[DestReplyMessage.MESSAGE_TYPE] = new DestReplyMessageHandler(context);
        _handlers[BandwidthLimitsMessage.MESSAGE_TYPE] = new BWLimitsMessageHandler(context);
    }

    public I2CPMessageHandler getHandler(int messageTypeId) {
        if ( (messageTypeId < 0) || (messageTypeId >= _handlers.length) ) return null;
        return _handlers[messageTypeId];
    }
}
