package net.i2p.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.HashMap;
import java.util.Map;

import net.i2p.data.i2cp.DisconnectMessage;
import net.i2p.data.i2cp.MessagePayloadMessage;
import net.i2p.data.i2cp.MessageStatusMessage;
import net.i2p.data.i2cp.RequestLeaseSetMessage;
import net.i2p.data.i2cp.SessionStatusMessage;
import net.i2p.data.i2cp.SetDateMessage;
import net.i2p.util.Log;
import net.i2p.I2PAppContext;

/**
 * Contains a map of message handlers that a session will want to use
 *
 * @author jrandom
 */
class I2PClientMessageHandlerMap {
    private final static Log _log = new Log(I2PClientMessageHandlerMap.class);
    /** map of message type id --> I2CPMessageHandler */
    private Map _handlers;

    public I2PClientMessageHandlerMap(I2PAppContext context) {
        _handlers = new HashMap();
        _handlers.put(new Integer(DisconnectMessage.MESSAGE_TYPE), new DisconnectMessageHandler(context));
        _handlers.put(new Integer(SessionStatusMessage.MESSAGE_TYPE), new SessionStatusMessageHandler(context));
        _handlers.put(new Integer(RequestLeaseSetMessage.MESSAGE_TYPE), new RequestLeaseSetMessageHandler(context));
        _handlers.put(new Integer(MessagePayloadMessage.MESSAGE_TYPE), new MessagePayloadMessageHandler(context));
        _handlers.put(new Integer(MessageStatusMessage.MESSAGE_TYPE), new MessageStatusMessageHandler(context));
        _handlers.put(new Integer(SetDateMessage.MESSAGE_TYPE), new SetDateMessageHandler(context));
    }

    public I2CPMessageHandler getHandler(int messageTypeId) {
        I2CPMessageHandler handler = (I2CPMessageHandler) _handlers.get(new Integer(messageTypeId));
        return handler;
    }
}