package net.i2p.client;

import java.util.Properties;

import net.i2p.data.Destination;
import net.i2p.data.i2cp.HostReplyMessage;

/**
 * The return value of I2PSession.lookupDest2()
 *
 * @since 0.9.43
 */
public interface LookupResult {

    /** getDestination() will be non-null */
    public static final int RESULT_SUCCESS = HostReplyMessage.RESULT_SUCCESS;

    /** general failure, probably a local hostname lookup failure, or a b32 lookup timeout */
    public static final int RESULT_FAILURE = HostReplyMessage.RESULT_FAILURE;

    /**
     * b33 requires a lookup password but the router does not have it cached;
     * please supply in a blinding info message
     */
    public static final int RESULT_SECRET_REQUIRED = HostReplyMessage.RESULT_SECRET_REQUIRED;

    /**
     * b33 requires per-client auth private key but the router does not have it cached;
     * please supply in a blinding info message
     */
    public static final int RESULT_KEY_REQUIRED = HostReplyMessage.RESULT_KEY_REQUIRED;

    /**
     * b33 requires a lookup password and per-client auth private key but the router does not have them cached;
     * please supply in a blinding info message
     */
    public static final int RESULT_SECRET_AND_KEY_REQUIRED = HostReplyMessage.RESULT_SECRET_AND_KEY_REQUIRED;

    /**
     * b33 requires per-client auth private key, the router has a key, but decryption failed;
     * please supply a new key in a blinding info message
     */
    public static final int RESULT_DECRYPTION_FAILURE = HostReplyMessage.RESULT_DECRYPTION_FAILURE;

    /**
     * See proposal 167
     * @since 0.9.70
     */
    public static final int RESULT_LEASESET_LOOKUP_FAILURE = HostReplyMessage.RESULT_LEASESET_LOOKUP_FAILURE;

    /**
     * See proposal 167
     * @since 0.9.70
     */
    public static final int RESULT_LOOKUP_TYPE_UNSUPPORTED = HostReplyMessage.RESULT_LOOKUP_TYPE_UNSUPPORTED;

    /**
     * For async calls only. Nonce will be non-zero and destination will be null.
     * Callback will be called later with the final result and the same nonce.
     *
     * @since 0.9.67
     */
    public static final int RESULT_DEFERRED = -1;

    /**
     * @return zero for success, nonzero for failure
     */
    public int getResultCode();

    /**
     * @return Destination on success, null on failure
     */
    public Destination getDestination();

    /**
     * See proposal 167
     * @return options from leaseset or null
     * @since 0.9.70
     */
    public Properties getOptions();

    /**
     * For async calls only. Nonce will be non-zero.
     * Callback will be called later with the final result and the same nonce.
     *
     * @since 0.9.67
     */
    public int getNonce();
}
