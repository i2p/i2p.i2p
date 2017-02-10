package net.i2p.client.streaming;

import java.net.SocketException;

import net.i2p.I2PAppContext;
import net.i2p.client.SendMessageStatusListener;
import net.i2p.data.i2cp.MessageStatusMessage;
import net.i2p.util.Translate;

/**
 *  An I2P-specific IOException thrown from input and output streams,
 *  with a stored status code to be used for programmatic responses.
 *
 *  @since 0.9.14
 */
public class I2PSocketException extends SocketException {

    private final int _status;
    private static final int CUSTOM = -1;
    private static final String BUNDLE_NAME = "net.i2p.client.streaming.messages";

    /**
     *  Router and I2CP status codes are 0 - 511. Start ours at 512.
     *  @since 0.9.19
     */
    public static final int STATUS_CONNECTION_RESET = 512;

    /**
     *  Use canned message for this status code.
     *
     *  Standard codes from the router are 0-255, defined in MessageStatusMessage.
     *  Standard codes from client-side I2CP are 256-511, defined in SendMessageStatusListener.
     *  Standard codes from streaming are 512-767, defined here.
     *
     *  @param status &gt;= 0 from MessageStatusMessage or SendMessageStatusListener
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
                return _x("Message timeout");

            case MessageStatusMessage.STATUS_SEND_FAILURE_LOCAL:
                return _x("Failed delivery to local destination");

            case MessageStatusMessage.STATUS_SEND_FAILURE_ROUTER:
                return _x("Local router failure");

            case MessageStatusMessage.STATUS_SEND_FAILURE_NETWORK:
                return _x("Local network failure");

            case MessageStatusMessage.STATUS_SEND_FAILURE_BAD_SESSION:
                return _x("Session closed");

            case MessageStatusMessage.STATUS_SEND_FAILURE_BAD_MESSAGE:
                return _x("Invalid message");

            case MessageStatusMessage.STATUS_SEND_FAILURE_BAD_OPTIONS:
                return _x("Invalid message options");

            case MessageStatusMessage.STATUS_SEND_FAILURE_OVERFLOW:
                return _x("Buffer overflow");

            case MessageStatusMessage.STATUS_SEND_FAILURE_EXPIRED:
                return _x("Message expired");

            case MessageStatusMessage.STATUS_SEND_FAILURE_LOCAL_LEASESET:
                return _x("Local lease set invalid");

            case MessageStatusMessage.STATUS_SEND_FAILURE_NO_TUNNELS:
                return _x("No local tunnels");

            case MessageStatusMessage.STATUS_SEND_FAILURE_UNSUPPORTED_ENCRYPTION:
                return _x("Unsupported encryption options");

            case MessageStatusMessage.STATUS_SEND_FAILURE_DESTINATION:
                return _x("Invalid destination");

            case MessageStatusMessage.STATUS_SEND_FAILURE_BAD_LEASESET:
                return _x("Local router failure");

            case MessageStatusMessage.STATUS_SEND_FAILURE_EXPIRED_LEASESET:
                return _x("Destination lease set expired");

            case MessageStatusMessage.STATUS_SEND_FAILURE_NO_LEASESET:
                return _x("Destination lease set not found");

            case SendMessageStatusListener.STATUS_CANCELLED:
                return _x("Local destination shutdown");

            case STATUS_CONNECTION_RESET:
                return _x("Connection was reset");

            case CUSTOM:
                return super.getMessage();

            default:
                // Translate this one here, can't do it later
                return _t("Failure code") + ": " + _status;
        }
    }

    /**
     *  Translated
     */
    @Override
    public String getLocalizedMessage() {
        String s = getMessage();
        if (s == null)
            return null;
        return _t(s);
    }

    /**
     *  Translate
     */
    private static String _t(String s) {
        return Translate.getString(s, I2PAppContext.getGlobalContext(), BUNDLE_NAME);
    }

    /**
     *  Tag for translation
     */
    private static String _x(String s) {
        return s;
    }
}
