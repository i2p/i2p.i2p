package net.i2p.sam;

import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;
import net.i2p.util.PasswordManager;

/**
 *
 * This is the "default" implementation of the SAMSecureSession @interface
 * that behaves exactly like SAM without interactive authentication. It uses
 * the i2cp username and password properties for authentication. Implementers
 * can add their own means of authentication by substituting this interface
 * for their own.
 *
 * @since 1.8.0
 */
public class SAMSecureSession implements SAMSecureSessionInterface {
    private final Log log = I2PAppContext.getGlobalContext().logManager().getLog(SAMHandlerFactory.class);

    /**
     * Authenticate based on the i2cp username/password.
     *
     * @since 1.8.0
     */
    public boolean approveOrDenySecureSession(Properties i2cpProps, Properties props) throws SAMException {
        String user = props.getProperty("USER");
        String pw = props.getProperty("PASSWORD");
        if (user == null || pw == null) {
            if (user == null)
                log.logAlways(Log.WARN, "SAM authentication failed");
            else
                log.logAlways(Log.WARN, "SAM authentication failed, user: " + user);
            throw new SAMException("USER and PASSWORD required");
        }
        String savedPW = i2cpProps.getProperty(SAMBridge.PROP_PW_PREFIX + user + SAMBridge.PROP_PW_SUFFIX);
        if (savedPW == null) {
            log.logAlways(Log.WARN, "SAM authentication failed, user: " + user);
            throw new SAMException("Authorization failed");
        }
        PasswordManager pm = new PasswordManager(I2PAppContext.getGlobalContext());
        if (!pm.checkHash(savedPW, pw)) {
            log.logAlways(Log.WARN, "SAM authentication failed, user: " + user);
            throw new SAMException("Authorization failed");
        }
        return true;
    }
}
