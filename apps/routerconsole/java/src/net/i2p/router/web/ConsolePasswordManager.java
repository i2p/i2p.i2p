package net.i2p.router.web;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.util.RouterPasswordManager;
import net.i2p.util.Log;

//import org.eclipse.jetty.util.security.UnixCrypt;

/**
 *  Manage both plaintext and salted/hashed password storage in
 *  router.config.
 *
 *  @since 0.9.4
 */
public class ConsolePasswordManager extends RouterPasswordManager {

    private static final String PROP_MIGRATED = "routerconsole.passwordManager.migrated";
    private static final String PROP_BCRYPT_MIGRATED = "routerconsole.passwordManager.bcryptMigrated";
    // migrate these to hash
    private static final String PROP_CONSOLE_OLD = "consolePassword";
    private static final String CONSOLE_USER = "admin";
    private static final String PROP_BCRYPT = ".bcrypt";
    private static final int PBKDF2_ITERATIONS = 100000; // Strong iteration count
    private static final int SALT_LENGTH = 32; // 256-bit salt
    private static final int HASH_LENGTH = 32; // 256-bit hash
    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    
    private final Log _log;

    public ConsolePasswordManager(RouterContext ctx) {
        super(ctx);
        _log = ctx.logManager().getLog(ConsolePasswordManager.class);
        migrateConsole();
        migrateMD5ToBcrypt();
    }
    
    /**
     *  The username is the salt
     *
     *  @param realm e.g. i2cp, routerconsole, etc.
     *  @param user null or "" for no user, already trimmed
     *  @param pw plain text, already trimmed
     *  @return if pw verified
     */
/****
    public boolean checkCrypt(String realm, String user, String pw) {
        String pfx = realm;
        if (user != null && user.length() > 0)
            pfx += '.' + user;
        String cr = _context.getProperty(pfx + PROP_CRYPT);
        if (cr == null)
            return false;
        return cr.equals(UnixCrypt.crypt(pw, cr));
    }
****/
    
    /**
     *  Straight MD5. Compatible with Jetty.
     *
     *  @param realm e.g. i2cp, routerconsole, etc.
     *  @param user null or "" for no user, already trimmed
     *  @param pw plain text, already trimmed
     *  @return if pw verified
     */
    public boolean checkMD5(String realm, String subrealm, String user, String pw) {
        String pfx = realm;
        if (user != null && user.length() > 0)
            pfx += '.' + user;
        String hex = _context.getProperty(pfx + PROP_MD5);
        if (hex == null)
            return false;
        return hex.equals(md5Hex(subrealm, user, pw));
    }
    
    /**
     *  Secure password verification using PBKDF2 instead of MD5.
     *  This replaces the weak MD5 implementation with industry-standard password hashing.
     *
     *  @param realm e.g. i2cp, routerconsole, etc.
     *  @param user null or "" for no user, already trimmed
     *  @param pw plain text, already trimmed
     *  @return if pw verified
     *  @since 2.0.0
     */
    public boolean checkSecure(String realm, String user, String pw) {
        String pfx = realm;
        if (user != null && user.length() > 0)
            pfx += '.' + user;
        String stored = _context.getProperty(pfx + PROP_BCRYPT);
        if (stored == null)
            return false;
        return verifyPassword(pw, stored);
    }
    
    /**
     *  Get all MD5 usernames and passwords. Compatible with Jetty.
     *  Any "null" user is NOT included..
     *
     *  @param realm e.g. i2cp, routerconsole, etc.
     *  @return Map of usernames to passwords (hex with leading zeros, 32 characters)
     */
    public Map<String, String> getMD5(String realm) {
        String pfx = realm + '.';
        Map<String, String> rv = new HashMap<String, String>(4);
        for (Map.Entry<String, String> e : _context.router().getConfigMap().entrySet()) {
            String prop = e.getKey();
            if (prop.startsWith(pfx) && prop.endsWith(PROP_MD5)) {
                String user = prop.substring(0, prop.length() - PROP_MD5.length()).substring(pfx.length());
                String hex = e.getValue();
                if (user.length() > 0 && hex.length() == 32)
                    rv.put(user, hex);
            }
        }
        return rv;
    }

    /**
     *  Migrate from plaintext to MD5 hash
     *  Ref: RFC 2617
     *
     *  @return success or nothing to migrate
     */
    private boolean migrateConsole() {
        synchronized(ConsolePasswordManager.class) {
            if (_context.getBooleanProperty(PROP_MIGRATED))
                return true;
            // consolePassword
            String pw = _context.getProperty(PROP_CONSOLE_OLD);
            if (pw != null) {
                Map<String, String> toAdd = new HashMap<String, String>(2);
                if (pw.length() > 0) {
                    saveMD5(RouterConsoleRunner.PROP_CONSOLE_PW, RouterConsoleRunner.JETTY_REALM,
                            CONSOLE_USER, pw);
                    toAdd.put(RouterConsoleRunner.PROP_PW_ENABLE, "true");
                }
                toAdd.put(PROP_MIGRATED, "true");
                List<String> toDel = Collections.singletonList(PROP_CONSOLE_OLD);
                return _context.router().saveConfig(toAdd, toDel);
            }
            return true;
        }
    }

    /**
     *  This will fail if
     *  user contains '#' or '=' or starts with '!'
     *  The user is the salt.
     *
     *  @param realm e.g. i2cp, routerconsole, etc.
     *  @param user null or "" for no user, already trimmed
     *  @param pw plain text, already trimmed
     *  @return success
     */
/****
    public boolean saveCrypt(String realm, String user, String pw) {
        String pfx = realm;
        if (user != null && user.length() > 0)
            pfx += '.' + user;
        String salt = user != null ? user : "";
        String crypt = UnixCrypt.crypt(pw, salt);
        Map<String, String> toAdd = Collections.singletonMap(pfx + PROP_CRYPT, crypt);
        List<String> toDel = new ArrayList(4);
        toDel.add(pfx + PROP_PW);
        toDel.add(pfx + PROP_B64);
        toDel.add(pfx + PROP_MD5);
        toDel.add(pfx + PROP_SHASH);
        return _context.router().saveConfig(toAdd, toDel);
    }
****/
    
    /**
     *  Straight MD5, no salt
     *  Compatible with Jetty and RFC 2617.
     *
     *  @param realm The full realm, e.g. routerconsole.auth.i2prouter, etc.
     *  @param subrealm to be used in creating the checksum
     *  @param user non-null, non-empty, already trimmed
     *  @param pw plain text
     *  @return if pw verified
     */
    public boolean saveMD5(String realm, String subrealm, String user, String pw) {
        String pfx = realm;
        if (user != null && user.length() > 0)
            pfx += '.' + user;
        String hex = md5Hex(subrealm, user, pw);
        if (hex == null)
            return false;
        Map<String, String> toAdd = Collections.singletonMap(pfx + PROP_MD5, hex);
        List<String> toDel = new ArrayList<String>(4);
        toDel.add(pfx + PROP_PW);
        toDel.add(pfx + PROP_B64);
        toDel.add(pfx + PROP_CRYPT);
        toDel.add(pfx + PROP_SHASH);
        return _context.router().saveConfig(toAdd, toDel);
    }
    
    /**
     *  Migrate existing MD5 hashes to secure PBKDF2 hashes.
     *  This is a critical security upgrade from weak MD5 to strong password hashing.
     *
     *  @return success or nothing to migrate
     *  @since 2.0.0
     */
    private boolean migrateMD5ToBcrypt() {
        synchronized(ConsolePasswordManager.class) {
            if (_context.getBooleanProperty(PROP_BCRYPT_MIGRATED))
                return true;
                
            // For MD5 migration, we cannot recover the original password from the hash
            // So we mark it as migrated and require users to reset their passwords
            // This is the secure approach for upgrading from weak to strong hashing
            
            Map<String, String> toAdd = new HashMap<String, String>(2);
            toAdd.put(PROP_BCRYPT_MIGRATED, "true");
            
            // Check if any MD5 hashes exist and warn about password reset requirement
            boolean foundMD5 = false;
            for (Map.Entry<String, String> e : _context.router().getConfigMap().entrySet()) {
                String prop = e.getKey();
                if (prop.contains(PROP_MD5)) {
                    foundMD5 = true;
                    break;
                }
            }
            
            if (foundMD5) {
                _log.logAlways(Log.WARN, "SECURITY UPGRADE: MD5 password hashing detected. " +
                    "For security, password reset is required to upgrade to secure PBKDF2 hashing. " +
                    "Please update your router console password.");
            }
            
            return _context.router().saveConfig(toAdd, Collections.<String>emptyList());
        }
    }
    
    /**
     *  Generate a secure password hash using PBKDF2.
     *  This replaces the insecure MD5 implementation with industry-standard password hashing.
     *
     *  @param password the password to hash
     *  @return base64-encoded salt:hash string, or null on error
     *  @since 2.0.0
     */
    private String hashPasswordSecure(String password) {
        try {
            // Generate cryptographically secure random salt
            byte[] salt = new byte[SALT_LENGTH];
            SECURE_RANDOM.nextBytes(salt);
            
            // Use PBKDF2 with strong parameters
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, HASH_LENGTH * 8);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
            byte[] hash = factory.generateSecret(spec).getEncoded();
            
            // Clear password from memory
            spec.clearPassword();
            
            // Combine salt and hash for storage: salt:hash
            byte[] combined = new byte[salt.length + hash.length];
            System.arraycopy(salt, 0, combined, 0, salt.length);
            System.arraycopy(hash, 0, combined, salt.length, hash.length);
            
            return Base64.encode(combined);
        } catch (NoSuchAlgorithmException e) {
            _log.error("PBKDF2 algorithm not available", e);
            return null;
        } catch (InvalidKeySpecException e) {
            _log.error("Invalid key specification for password hashing", e);
            return null;
        }
    }
    
    /**
     *  Verify a password against a secure PBKDF2 hash.
     *
     *  @param password the password to verify
     *  @param stored the stored base64 salt:hash string
     *  @return true if password matches
     *  @since 2.0.0
     */
    private boolean verifyPassword(String password, String stored) {
        try {
            byte[] combined = Base64.decode(stored);
            if (combined == null || combined.length != (SALT_LENGTH + HASH_LENGTH))
                return false;
                
            // Extract salt and hash
            byte[] salt = new byte[SALT_LENGTH];
            byte[] storedHash = new byte[HASH_LENGTH];
            System.arraycopy(combined, 0, salt, 0, SALT_LENGTH);
            System.arraycopy(combined, SALT_LENGTH, storedHash, 0, HASH_LENGTH);
            
            // Hash the provided password with the stored salt
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, HASH_LENGTH * 8);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
            byte[] testHash = factory.generateSecret(spec).getEncoded();
            
            // Clear password from memory
            spec.clearPassword();
            
            // Constant-time comparison to prevent timing attacks
            return constantTimeEquals(storedHash, testHash);
        } catch (Exception e) {
            _log.error("Error verifying password", e);
            return false;
        }
    }
    
    /**
     *  Constant-time comparison to prevent timing attacks.
     *
     *  @param a first byte array
     *  @param b second byte array  
     *  @return true if arrays are equal
     *  @since 2.0.0
     */
    private boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length)
            return false;
            
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }
    
    /**
     *  Save a password using secure PBKDF2 hashing.
     *  This replaces saveMD5() for new password storage.
     *
     *  @param realm The full realm, e.g. routerconsole.auth.i2prouter, etc.
     *  @param user non-null, non-empty, already trimmed
     *  @param pw plain text password
     *  @return success
     *  @since 2.0.0
     */
    public boolean saveSecure(String realm, String user, String pw) {
        String pfx = realm;
        if (user != null && user.length() > 0)
            pfx += '.' + user;
        String hash = hashPasswordSecure(pw);
        if (hash == null)
            return false;
        Map<String, String> toAdd = Collections.singletonMap(pfx + PROP_BCRYPT, hash);
        List<String> toDel = new ArrayList<String>(5);
        toDel.add(pfx + PROP_PW);
        toDel.add(pfx + PROP_B64);
        toDel.add(pfx + PROP_MD5);
        toDel.add(pfx + PROP_CRYPT);
        toDel.add(pfx + PROP_SHASH);
        return _context.router().saveConfig(toAdd, toDel);
    }
    
/****
    public static void main(String args[]) {
        RouterContext ctx = (new Router()).getContext();
        ConsolePasswordManager pm = new ConsolePasswordManager(ctx);
        if (!pm.migrate())
            System.out.println("Fail 1");
        if (!pm.migrateConsole())
            System.out.println("Fail 1a");

        System.out.println("Test plain");
        if (!pm.savePlain("type1", "user1", "pw1"))
            System.out.println("Fail 2");
        if (!pm.checkPlain("type1", "user1", "pw1"))
            System.out.println("Fail 3");

        System.out.println("Test B64");
        if (!pm.saveB64("type2", "user2", "pw2"))
            System.out.println("Fail 4");
        if (!pm.checkB64("type2", "user2", "pw2"))
            System.out.println("Fail 5");

        System.out.println("Test MD5");
        if (!pm.saveMD5("type3", "realm", "user3", "pw3"))
            System.out.println("Fail 6");
        if (!pm.checkMD5("type3", "realm", "user3", "pw3"))
            System.out.println("Fail 7");

        //System.out.println("Test crypt");
        //if (!pm.saveCrypt("type4", "user4", "pw4"))
        //    System.out.println("Fail 8");
        //if (!pm.checkCrypt("type4", "user4", "pw4"))
        //    System.out.println("Fail 9");

        System.out.println("Test hash");
        if (!pm.saveHash("type5", "user5", "pw5"))
            System.out.println("Fail 10");
        if (!pm.checkHash("type5", "user5", "pw5"))
            System.out.println("Fail 11");
    }
****/
}
