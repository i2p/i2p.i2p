package net.i2p.i2pcontrol.security;
/*
 *  Copyright 2011 hottuna (dev@robertfoss.se)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

import net.i2p.I2PAppContext;
import net.i2p.crypto.SHA256Generator;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

import org.mindrot.jbcrypt.BCrypt;

import net.i2p.i2pcontrol.servlets.configuration.ConfigurationManager;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.security.KeyStore;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Iterator;

/**
 * Manage the password storing for I2PControl.
 */
public class SecurityManager {
    public final static String DEFAULT_AUTH_PASSWORD = "itoopie";
    private final HashMap<String, AuthToken> authTokens;
    private final SimpleTimer2.TimedEvent timer;
    private final KeyStore _ks;
    private final Log _log;
    private final ConfigurationManager _conf;
    private final I2PAppContext _context;

    /**
     *  @param ksp may be null (if webapp)
     */
    public SecurityManager(I2PAppContext ctx, KeyStoreProvider ksp, ConfigurationManager conf) {
        _context = ctx;
        _conf = conf;
        _log = ctx.logManager().getLog(SecurityManager.class);
        authTokens = new HashMap<String, AuthToken>();

        timer = new Sweeper();

        _ks = ksp != null ? ksp.getDefaultKeyStore() : null;
    }

    public void stopTimedEvents() {
        timer.cancel();
        synchronized (authTokens) {
            authTokens.clear();
        }
    }

    /**
     * Return the X509Certificate of the server as a Base64 encoded string.
     * @return base64 encode of X509Certificate
     */
/****  unused and incorrectly uses I2P Base64. Switch to CertUtil.exportCert() if needed.
    public String getBase64Cert() {
        X509Certificate caCert = KeyStoreProvider.readCert(_ks,
                                 KeyStoreProvider.DEFAULT_CERTIFICATE_ALIAS,
                                 KeyStoreProvider.DEFAULT_KEYSTORE_PASSWORD);
        return getBase64FromCert(caCert);
    }
****/

    /**
     * Return the X509Certificate as a base64 encoded string.
     * @param cert
     * @return base64 encode of X509Certificate
     */
/****  unused and incorrectly uses I2P Base64. Switch to CertUtil.exportCert() if needed.
    private static String getBase64FromCert(X509Certificate cert) {
        try {
            return Base64.encode(cert.getEncoded());
        } catch (CertificateEncodingException e) {
            e.printStackTrace();
        }
        return null;
    }
****/



    /**
     * Hash pwd with using BCrypt with the default salt.
     * @param pwd
     * @return BCrypt hash of salt and input string
     */
    public String getPasswdHash(String pwd) {
        String salt;
        synchronized(_conf) {
            salt = _conf.getConf("auth.salt", "");
            if (salt.equals("")) {
                salt = BCrypt.gensalt(10, _context.random());
                _conf.setConf("auth.salt", salt);
                _conf.writeConfFile();
            }
        }
        return BCrypt.hashpw(pwd, salt);
    }

    /**
     * Get saved password hash. Stores if not previously set.
     * @return BCrypt hash of salt and password
     * @since 0.12
     */
    private String getSavedPasswdHash() {
        String pw;
        synchronized(_conf) {
            pw = _conf.getConf("auth.password", "");
            if (pw.equals("")) {
                pw = getPasswdHash(DEFAULT_AUTH_PASSWORD);
                _conf.setConf("auth.password", pw);
                _conf.writeConfFile();
            }
        }
        return pw;
    }

    /**
     * Hash input one time with SHA-256, return Base64 encdoded string.
     * @param string
     * @return Base64 encoded string
     */
    public String getHash(String string) {
        SHA256Generator hashGen = _context.sha();
        byte[] bytes = string.getBytes();
        bytes = hashGen.calculateHash(bytes).toByteArray();
        return Base64.encode(bytes);
    }

    /**
     * Is this password correct?
     * @return true if password is valid.
     * @since 0.12
     */
    public boolean isValid(String pwd) {
        String storedPass = getSavedPasswdHash();
        byte[] p1 = DataHelper.getASCII(getPasswdHash(pwd));
        byte[] p2 = DataHelper.getASCII(storedPass);
        return p1.length == p2.length && DataHelper.eqCT(p1, 0, p2, 0, p1.length);
    }

    /**
     * Is this password correct?
     * @return true if password is valid.
     * @since 0.12
     */
    public boolean isDefaultPasswordValid() {
        return isValid(DEFAULT_AUTH_PASSWORD);
    }

    /**
     * Add a Authentication Token if the provided password is valid.
     * The token will be valid for one day.
     * @return AuthToken if password is valid. If password is invalid null will be returned.
     */
    public AuthToken validatePasswd(String pwd) {
        if (isValid(pwd)) {
            AuthToken token = new AuthToken(this, pwd);
            synchronized (authTokens) {
                authTokens.put(token.getId(), token);
            }
            return token;
        } else {
            return null;
        }
    }

    /**
     * Set new password. Old tokens will NOT remain valid, to encourage the new password being tested.
     * @param newPasswd
     * @return Returns true if a new password was set.
     */
    public boolean setPasswd(String newPasswd) {
        String newHash = getPasswdHash(newPasswd);
        String oldHash = getSavedPasswdHash();

        if (!newHash.equals(oldHash)) {
            _conf.setConf("auth.password", newHash);
            _conf.writeConfFile();
            synchronized (authTokens) {
                authTokens.clear();
            }
            return true;
        }
        return false;
    }

    /**
     * Checks whether the AuthToken with the given ID exists and if it does whether is has expired.
     * @param tokenID - The token to validate
     * @throws InvalidAuthTokenException
     * @throws ExpiredAuthTokenException
     */
    public void verifyToken(String tokenID) throws InvalidAuthTokenException, ExpiredAuthTokenException {
        synchronized (authTokens) {
            AuthToken token = authTokens.get(tokenID);
            if (token == null)
                throw new InvalidAuthTokenException("AuthToken with ID: " + tokenID + " couldn't be found.");
            if (!token.isValid()) {
                authTokens.remove(tokenID);
                throw new ExpiredAuthTokenException("AuthToken with ID: " + tokenID + " expired " + token.getExpiryTime(), token.getExpiryTime());
            }
        }
        // Everything is fine. :)
    }

    /**
     * Clean up old authorization tokens to keep the token store slim and fit.
     * @author hottuna
     *
     */
    private class Sweeper extends SimpleTimer2.TimedEvent {
        // Start running periodic task after 1 day, run periodically every 30 minutes.
        public Sweeper() {
            super(_context.simpleTimer2(), AuthToken.VALIDITY_TIME * 24*60*60*1000L);
        }

        public void timeReached() {
            _log.debug("Starting cleanup job..");
            synchronized (authTokens) {
                for (Iterator<AuthToken> iter = authTokens.values().iterator(); iter.hasNext(); ) {
                    AuthToken token = iter.next();
                    if (!token.isValid())
                        iter.remove();
                }
            }
            _log.debug("Cleanup job done.");
            schedule(30*60*1000L);
        }
    }
}
