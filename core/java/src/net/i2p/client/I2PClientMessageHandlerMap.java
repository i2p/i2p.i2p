package net.i2p.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.i2cp.*;
import net.i2p.util.Log;

import java.util.Map;
import java.util.HashMap;

/**
 * Contains a map of message handlers that a session will want to use
 *
 * @author jrandom
 */
class I2PClientMessageHandlerMap {
    private final static Log _log = new Log(I2PClientMessageHandlerMap.class);
    /** map of message type id --> I2CPMessageHandler */
    private static Map _handlers;
    
    static {
        _handlers = new HashMap();
        _handlers.put(new Integer(DisconnectMessage.MESSAGE_TYPE), new DisconnectMessageHandler());
        _handlers.put(new Integer(SessionStatusMessage.MESSAGE_TYPE), new SessionStatusMessageHandler());
        _handlers.put(new Integer(RequestLeaseSetMessage.MESSAGE_TYPE), new RequestLeaseSetMessageHandler());
        _handlers.put(new Integer(MessagePayloadMessage.MESSAGE_TYPE), new MessagePayloadMessageHandler());
        _handlers.put(new Integer(MessageStatusMessage.MESSAGE_TYPE), new MessageStatusMessageHandler());
        _handlers.put(new Integer(SetDateMessage.MESSAGE_TYPE), new SetDateMessageHandler());
    }
    
    public static I2CPMessageHandler getHandler(int messageTypeId) {
        I2CPMessageHandler handler = (I2CPMessageHandler)_handlers.get(new Integer(messageTypeId));
        return handler;
    }
}
