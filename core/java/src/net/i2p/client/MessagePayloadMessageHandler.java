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
import net.i2p.data.DataFormatException;
import net.i2p.data.Payload;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.MessagePayloadMessage;
import net.i2p.data.i2cp.ReceiveMessageEndMessage;

/**
 * Handle I2CP MessagePayloadMessages from the router delivering the contents
 * of a message by accepting it, decrypting the payload, adding it to the set of
 * recieved messages, and telling the router that it has been recieved correctly.
 *
 * @author jrandom
 */
class MessagePayloadMessageHandler extends HandlerImpl {
    public MessagePayloadMessageHandler(I2PAppContext context) {
        super(context, MessagePayloadMessage.MESSAGE_TYPE);
    }

    public void handleMessage(I2CPMessage message, I2PSessionImpl session) {
        _log.debug("Handle message " + message);
        try {
            MessagePayloadMessage msg = (MessagePayloadMessage) message;
            MessageId id = msg.getMessageId();
            Payload payload = decryptPayload(msg, session);
            session.addNewMessage(msg);

            ReceiveMessageEndMessage m = new ReceiveMessageEndMessage();
            m.setMessageId(id);
            m.setSessionId(msg.getSessionId());
            session.sendMessage(m);
        } catch (DataFormatException dfe) {
            session.propogateError("Error handling a new payload message", dfe);
        } catch (I2PSessionException ise) {
            session.propogateError("Error handling a new payload message", ise);
        }
    }

    /**
     * Decrypt the payload
     */
    private Payload decryptPayload(MessagePayloadMessage msg, I2PSessionImpl session) throws DataFormatException {
        Payload payload = msg.getPayload();
        byte[] data = _context.elGamalAESEngine().decrypt(payload.getEncryptedData(), session.getDecryptionKey());
        if (data == null) {
            _log
                .error("Error decrypting the payload to public key "
                       + session.getMyDestination().getPublicKey().toBase64() + "\nPayload: " + payload.calculateHash());
            throw new DataFormatException("Unable to decrypt the payload");
        }
        payload.setUnencryptedData(data);
        return payload;
    }
}