package net.i2p.data.i2cp;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;

/**
 * Handle messages from the server for the client or vice versa
 *
 */
public class I2CPMessageHandler {

    /**
     * Read an I2CPMessage from the stream and return the fully populated object.
     * 
     * @param in I2CP input stream
     * @return Fully populated I2CPMessage
     * @throws IOException if there is an IO problem reading from the stream
     * @throws I2CPMessageException if there is a problem handling the particular
     *          message - if it is an unknown type or has improper formatting, etc.
     */
    public static I2CPMessage readMessage(InputStream in) throws IOException, I2CPMessageException {
        int length = -1;
        try {
            length = (int) DataHelper.readLong(in, 4);
        } catch (DataFormatException dfe) {
            throw new IOException("Connection closed");
        }
        try {
            if (length < 0) throw new I2CPMessageException("Invalid message length specified");
            int type = (int) DataHelper.readLong(in, 1);
            I2CPMessage msg = createMessage(type);
            msg.readMessage(in, length, type);
            return msg;
        } catch (DataFormatException dfe) {
            throw new I2CPMessageException("Error reading the message", dfe);
        }
    }

    /**
     * Yes, this is fairly ugly, but its the only place it ever happens.  
     *
     */
    private static I2CPMessage createMessage(int type) throws IOException,
                                                       I2CPMessageException {
        switch (type) {
        case CreateLeaseSetMessage.MESSAGE_TYPE:
            return new CreateLeaseSetMessage();
        case CreateSessionMessage.MESSAGE_TYPE:
            return new CreateSessionMessage();
        case DestroySessionMessage.MESSAGE_TYPE:
            return new DestroySessionMessage();
        case DisconnectMessage.MESSAGE_TYPE:
            return new DisconnectMessage();
        case MessageStatusMessage.MESSAGE_TYPE:
            return new MessageStatusMessage();
        case MessagePayloadMessage.MESSAGE_TYPE:
            return new MessagePayloadMessage();
        case ReceiveMessageBeginMessage.MESSAGE_TYPE:
            return new ReceiveMessageBeginMessage();
        case ReceiveMessageEndMessage.MESSAGE_TYPE:
            return new ReceiveMessageEndMessage();
        case ReconfigureSessionMessage.MESSAGE_TYPE:
            return new ReconfigureSessionMessage();
        case ReportAbuseMessage.MESSAGE_TYPE:
            return new ReportAbuseMessage();
        case RequestLeaseSetMessage.MESSAGE_TYPE:
            return new RequestLeaseSetMessage();
        case SendMessageMessage.MESSAGE_TYPE:
            return new SendMessageMessage();
        case SendMessageExpiresMessage.MESSAGE_TYPE:
            return new SendMessageExpiresMessage();
        case SessionStatusMessage.MESSAGE_TYPE:
            return new SessionStatusMessage();
        case GetDateMessage.MESSAGE_TYPE:
            return new GetDateMessage();
        case SetDateMessage.MESSAGE_TYPE:
            return new SetDateMessage();
        case DestLookupMessage.MESSAGE_TYPE:
            return new DestLookupMessage();
        case DestReplyMessage.MESSAGE_TYPE:
            return new DestReplyMessage();
        case GetBandwidthLimitsMessage.MESSAGE_TYPE:
            return new GetBandwidthLimitsMessage();
        case BandwidthLimitsMessage.MESSAGE_TYPE:
            return new BandwidthLimitsMessage();
        default:
            throw new I2CPMessageException("The type " + type + " is an unknown I2CP message");
        }
    }

/***
    public static void main(String args[]) {
        try {
            I2CPMessage msg = readMessage(new FileInputStream(args[0]));
            System.out.println(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
***/
}
