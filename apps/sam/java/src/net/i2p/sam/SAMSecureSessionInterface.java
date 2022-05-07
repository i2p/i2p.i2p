package net.i2p.sam;

import java.util.Properties;

/**
 * SAMSecureSessionInterface is used for implementing interactive authentication
 * to SAM applications. It needs to be implemented by a class for Desktop and
 * Android applications and passed to the SAM bridge when constructed.
 *
 * It is NOT required that a SAM API have this feature. It is recommended that
 * it be implemented for platforms which have a very hostile malware landscape
 * like Android.
 *
 * @since 1.8.0
 */
public interface SAMSecureSessionInterface {
    /**
     * Within this function, read and accept input from a user to approve a SAM
     * connection. Return false by default
     *
     * if the connection is approved by user input:
     *
     * @since 1.8.0
     * @return true
     */
    public boolean approveOrDenySecureSession(Properties i2cpProps, Properties props) throws SAMException;
}
