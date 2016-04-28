package net.i2p.router.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.SessionKey;
import net.i2p.router.RouterContext;
import net.i2p.util.PasswordManager;

/**
 *  Manage both plaintext and salted/hashed password storage in
 *  router.config.
 *
 *  @since 0.9.4
 */
public class RouterPasswordManager extends PasswordManager {
    protected final RouterContext _context;

    private static final String PROP_MIGRATED = "router.passwordManager.migrated";
    // migrate these to hash
    private static final String PROP_I2CP_OLD_PW = "i2cp.password";
    private static final String PROP_I2CP_OLD_USER = "i2cp.username";
    private static final String PROP_I2CP_NEW = "i2cp.auth";
/****
    // migrate these to b64
    private static final String[] MIGRATE_FROM = {
        // This has a separate router.reseedProxy.username prop,
        // so let's not mess with it
        "router.reseedProxy.password", 
        // Don't migrate these until we have a console form for them,
        // which we aren't likely to ever bother with
        "routerconsole.keyPassword",
        "routerconsole.keystorePassword",
        "i2cp.keyPassword",
        "i2cp.keystorePassword"
    };
    private static final String[] MIGRATE_TO = {
        "router.reseedProxy.auth", 
        "routerconsole.ssl.key.auth",
        "routerconsole.ssl.keystore.auth",
        "i2cp.ssl.key.auth",
        "i2cp.ssl.keystore.auth"
    };
****/

    public RouterPasswordManager(RouterContext ctx) {
        super(ctx);
        _context = ctx;
        migrate();
    }
    
    /**
     *  Migrate from plaintext to salt/hash
     *
     *  @return success or nothing to migrate
     */
    protected boolean migrate() {
        synchronized(RouterPasswordManager.class) {
            if (_context.getBooleanProperty(PROP_MIGRATED))
                return true;
            // i2cp.password
            String user = _context.getProperty(PROP_I2CP_OLD_USER);
            String pw = _context.getProperty(PROP_I2CP_OLD_PW);
            if (pw != null && user != null && pw.length() > 0 && user.length() > 0) {
                    saveHash(PROP_I2CP_NEW, user, pw);
            }
            // obfuscation of plaintext passwords
            Map<String, String> toAdd = new HashMap<String, String>(5);
            List<String> toDel = new ArrayList<String>(5);
         /****
            for (int i = 0; i < MIGRATE_FROM.length; i++) {
                if ((pw = _context.getProperty(MIGRATE_FROM[i])) != null) {
                    toAdd.put(MIGRATE_TO[i], Base64.encode(DataHelper.getUTF8(pw)));
                    toDel.add(MIGRATE_FROM[i]);
                }
            }
          ****/
            toDel.add(PROP_I2CP_OLD_USER);
            toDel.add(PROP_I2CP_OLD_PW);
            toAdd.put(PROP_MIGRATED, "true");
            return _context.router().saveConfig(toAdd, toDel);
        }
    }

    /**
     *  Same as saveHash()
     *
     *  @param realm e.g. i2cp, routerconsole, etc.
     *  @param user null or "" for no user, already trimmed
     *  @param pw plain text, already trimmed
     *  @return success
     */
    public boolean save(String realm, String user, String pw) {
        return saveHash(realm, user, pw);
    }
    
    /**
     *  This will fail if pw contains a '#'
     *  or if user contains '#' or '=' or starts with '!'
     *
     *  @param realm e.g. i2cp, routerconsole, etc.
     *  @param user null or "" for no user, already trimmed
     *  @param pw plain text, already trimmed
     *  @return success
     */
    public boolean savePlain(String realm, String user, String pw) {
        String pfx = realm;
        if (user != null && user.length() > 0)
            pfx += '.' + user;
        Map<String, String> toAdd = Collections.singletonMap(pfx + PROP_PW, pw);
        List<String> toDel = new ArrayList<String>(4);
        toDel.add(pfx + PROP_B64);
        toDel.add(pfx + PROP_MD5);
        toDel.add(pfx + PROP_CRYPT);
        toDel.add(pfx + PROP_SHASH);
        return _context.router().saveConfig(toAdd, toDel);
    }
    
    
    /**
     *  This will fail if
     *  if user contains '#' or '=' or starts with '!'
     *
     *  @param realm e.g. i2cp, routerconsole, etc.
     *  @param user null or "" for no user, already trimmed
     *  @param pw plain text, already trimmed
     *  @return success
     */
    public boolean saveB64(String realm, String user, String pw) {
        String pfx = realm;
        if (user != null && user.length() > 0)
            pfx += '.' + user;
        String b64 = Base64.encode(DataHelper.getUTF8(pw));
        Map<String, String> toAdd = Collections.singletonMap(pfx + PROP_B64, b64);
        List<String> toDel = new ArrayList<String>(4);
        toDel.add(pfx + PROP_PW);
        toDel.add(pfx + PROP_MD5);
        toDel.add(pfx + PROP_CRYPT);
        toDel.add(pfx + PROP_SHASH);
        return _context.router().saveConfig(toAdd, toDel);
    }
    
    /**
     *  This will fail if
     *  user contains '#' or '=' or starts with '!'
     *
     *  @param realm e.g. i2cp, routerconsole, etc.
     *  @param user null or "" for no user, already trimmed
     *  @param pw plain text, already trimmed
     *  @return success
     */
    public boolean saveHash(String realm, String user, String pw) {
        String pfx = realm;
        if (user != null && user.length() > 0)
            pfx += '.' + user;
        String shash = createHash(pw);
        Map<String, String> toAdd = Collections.singletonMap(pfx + PROP_SHASH, shash);
        List<String> toDel = new ArrayList<String>(4);
        toDel.add(pfx + PROP_PW);
        toDel.add(pfx + PROP_B64);
        toDel.add(pfx + PROP_MD5);
        toDel.add(pfx + PROP_CRYPT);
        return _context.router().saveConfig(toAdd, toDel);
    }

    /**
     *  Remove password, any kind.
     *
     *  @param realm e.g. i2cp, routerconsole, etc.
     *  @param user null or "" for no user, already trimmed
     *  @return success
     */
    public boolean remove(String realm, String user) {
        String pfx = realm;
        if (user != null && user.length() > 0)
            pfx += '.' + user;
        List<String> toDel = new ArrayList<String>(5);
        toDel.add(pfx + PROP_PW);
        toDel.add(pfx + PROP_B64);
        toDel.add(pfx + PROP_MD5);
        toDel.add(pfx + PROP_CRYPT);
        toDel.add(pfx + PROP_SHASH);
        return _context.router().saveConfig(null, toDel);
    }
}
