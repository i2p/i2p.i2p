package net.i2p.client.impl;

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
import net.i2p.data.i2cp.HostReplyMessage;
import net.i2p.data.i2cp.MessagePayloadMessage;
import net.i2p.data.i2cp.MessageStatusMessage;
import net.i2p.data.i2cp.RequestLeaseSetMessage;
import net.i2p.data.i2cp.RequestVariableLeaseSetMessage;
import net.i2p.data.i2cp.SessionStatusMessage;
import net.i2p.data.i2cp.SetDateMessage;

/**
 * Contains a map of message handlers that a session will want to use
 *
 * @author jrandom
 */
class I2PClientMessageHandlerMap {
    /** map of message type id --&gt; I2CPMessageHandler */
    protected final I2CPMessageHandler _handlers[];

    /** for extension */
    protected I2PClientMessageHandlerMap(int highest) {
        _handlers = new I2CPMessageHandler[highest+1];
    }

    public I2PClientMessageHandlerMap(I2PAppContext context) {
        // 39 = highest type expected from router
        // http://i2p-projekt.i2p/spec/i2cp#message-types
        this(HostReplyMessage.MESSAGE_TYPE);
        
        _handlers[DisconnectMessage.MESSAGE_TYPE] = new DisconnectMessageHandler(context);
        _handlers[SessionStatusMessage.MESSAGE_TYPE] = new SessionStatusMessageHandler(context);
        _handlers[RequestLeaseSetMessage.MESSAGE_TYPE] = new RequestLeaseSetMessageHandler(context);
        _handlers[MessagePayloadMessage.MESSAGE_TYPE] = new MessagePayloadMessageHandler(context);
        _handlers[MessageStatusMessage.MESSAGE_TYPE] = new MessageStatusMessageHandler(context);
        _handlers[SetDateMessage.MESSAGE_TYPE] = new SetDateMessageHandler(context);
        _handlers[DestReplyMessage.MESSAGE_TYPE] = new DestReplyMessageHandler(context);
        _handlers[BandwidthLimitsMessage.MESSAGE_TYPE] = new BWLimitsMessageHandler(context);
        _handlers[RequestVariableLeaseSetMessage.MESSAGE_TYPE] = new RequestVariableLeaseSetMessageHandler(context);
        _handlers[HostReplyMessage.MESSAGE_TYPE] = new HostReplyMessageHandler(context);
    }

    public I2CPMessageHandler getHandler(int messageTypeId) {
        if ( (messageTypeId < 0) || (messageTypeId >= _handlers.length) ) return null;
        return _handlers[messageTypeId];
    }
}
