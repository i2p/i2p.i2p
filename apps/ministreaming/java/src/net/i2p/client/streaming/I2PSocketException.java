package net.i2p.client.streaming;

import java.net.SocketException;

import net.i2p.client.SendMessageStatusListener;
import net.i2p.data.i2cp.MessageStatusMessage;

/**
 *  An I2P-specific IOException thrown from input and output streams.
 *  with a stored status code to be used for programmatic responses.
 *
 *  @since 0.9.14
 */
public class I2PSocketException extends SocketException {

    private final int _status;
    private static final int CUSTOM = -1;

    /**
     *  Use canned message for this status code
     *  @param status >= 0 from MessageStatusMessage or SendMessageStatusListener
     */
    public I2PSocketException(int status) {
        super();
        _status = status;
    }

    /**
     *  Use message provided
     */
    public I2PSocketException(String message) {
        super(message);
        _status = CUSTOM;
    }

    /**
     *  For programmatic action based on specific failure code
     *
     *  @return value from int constructor or -1 for String constructor
     */
    public int getStatus() {
        return _status;
    }

    /**
     *  For programmatic action based on specific failure code
     *
     *  @return canned message based on status in int constructor or message from String constructor
     */
    @Override
    public String getMessage() {
        switch (_status) {
            case MessageStatusMessage.STATUS_SEND_BEST_EFFORT_FAILURE:
            case MessageStatusMessage.STATUS_SEND_GUARANTEED_FAILURE:
                return "Message timeout";

            case MessageStatusMessage.STATUS_SEND_FAILURE_LOCAL:
                return "Failed delivery to local destination";

            case MessageStatusMessage.STATUS_SEND_FAILURE_ROUTER:
                return "Local router failure";

            case MessageStatusMessage.STATUS_SEND_FAILURE_NETWORK:
                return "Local network failure";

            case MessageStatusMessage.STATUS_SEND_FAILURE_BAD_SESSION:
                return "Session closed";

            case MessageStatusMessage.STATUS_SEND_FAILURE_BAD_MESSAGE:
                return "Invalid message";

            case MessageStatusMessage.STATUS_SEND_FAILURE_BAD_OPTIONS:
                return "Invalid message options";

            case MessageStatusMessage.STATUS_SEND_FAILURE_OVERFLOW:
                return "Buffer overflow";

            case MessageStatusMessage.STATUS_SEND_FAILURE_EXPIRED:
                return "Message expired";

            case MessageStatusMessage.STATUS_SEND_FAILURE_LOCAL_LEASESET:
                return "Local lease set invalid";

            case MessageStatusMessage.STATUS_SEND_FAILURE_NO_TUNNELS:
                return "No local tunnels";

            case MessageStatusMessage.STATUS_SEND_FAILURE_UNSUPPORTED_ENCRYPTION:
                return "Unsupported encryption options";

            case MessageStatusMessage.STATUS_SEND_FAILURE_DESTINATION:
                return "Invalid destination";

            case MessageStatusMessage.STATUS_SEND_FAILURE_BAD_LEASESET:
                return "Local router failure";

            case MessageStatusMessage.STATUS_SEND_FAILURE_EXPIRED_LEASESET:
                return "Destination lease set expired";

            case MessageStatusMessage.STATUS_SEND_FAILURE_NO_LEASESET:
                return "Destination lease set not found";

            case SendMessageStatusListener.STATUS_CANCELLED:
                return "Local destination shutdown";

            case CUSTOM:
                return super.getMessage();

            default:
                return "Failure code: " + _status;
        }
    }
}
