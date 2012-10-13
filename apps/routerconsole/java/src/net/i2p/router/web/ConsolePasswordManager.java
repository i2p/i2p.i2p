package net.i2p.router.web;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.util.RouterPasswordManager;

//import org.mortbay.jetty.security.UnixCrypt;

/**
 *  Manage both plaintext and salted/hashed password storage in
 *  router.config.
 *
 *  @since 0.9.4
 */
public class ConsolePasswordManager extends RouterPasswordManager {

    private static final String PROP_MIGRATED = "routerconsole.passwordManager.migrated";
    // migrate these to hash
    private static final String PROP_CONSOLE_OLD = "consolePassword";
    private static final String CONSOLE_USER = "admin";

    public ConsolePasswordManager(RouterContext ctx) {
        super(ctx);
        migrateConsole();
    }
    
    /**
     *  Checks both plaintext and hash
     *
     *  @param realm e.g. i2cp, routerconsole, etc.
     *  @param user null or "" for no user, already trimmed
     *  @param pw plain text, already trimmed
     *  @return if pw verified
     */
    public boolean check(String realm, String user, String pw) {
        return super.check(realm, user, pw) ||
               //checkCrypt(realm, user, pw) ||
               checkMD5(realm, user, pw);
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
    public boolean checkMD5(String realm, String user, String pw) {
        String pfx = realm;
        if (user != null && user.length() > 0)
            pfx += '.' + user;
        String hex = _context.getProperty(pfx + PROP_MD5);
        if (hex == null)
            return false;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(pw.getBytes("ISO-8859-1"));
            // must use the method that adds leading zeros
            return hex.equals(DataHelper.toString(md.digest()));
        } catch (UnsupportedEncodingException uee) {
            return false;
        } catch (NoSuchAlgorithmException nsae) {
            return false;
        }
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
        Map<String, String> rv = new HashMap(4);
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
    public boolean migrateConsole() {
        synchronized(ConsolePasswordManager.class) {
            if (_context.getBooleanProperty(PROP_MIGRATED))
                return true;
            // consolePassword
            String pw = _context.getProperty(PROP_CONSOLE_OLD);
            if (pw != null) {
                if (pw.length() > 0) {
                    pw = CONSOLE_USER + ':' + RouterConsoleRunner.JETTY_REALM + ':' + pw;
                    saveMD5(RouterConsoleRunner.PROP_CONSOLE_PW, CONSOLE_USER, pw);
                }
                Map toAdd = Collections.singletonMap(PROP_MIGRATED, "true");
                List toDel = Collections.singletonList(PROP_CONSOLE_OLD);
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
     *
     *  @param realm e.g. i2cp, routerconsole, etc.
     *  @param user null or "" for no user, already trimmed
     *  @param pw plain text, already trimmed
     *  @return if pw verified
     */
    public boolean saveMD5(String realm, String user, String pw) {
        String pfx = realm;
        if (user != null && user.length() > 0)
            pfx += '.' + user;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(pw.getBytes("ISO-8859-1"));
            String hex = DataHelper.toString(md.digest());
            Map<String, String> toAdd = Collections.singletonMap(pfx + PROP_MD5, hex);
            List<String> toDel = new ArrayList(4);
            toDel.add(pfx + PROP_PW);
            toDel.add(pfx + PROP_B64);
            toDel.add(pfx + PROP_CRYPT);
            toDel.add(pfx + PROP_SHASH);
            return _context.router().saveConfig(toAdd, toDel);
        } catch (UnsupportedEncodingException uee) {
            return false;
        } catch (NoSuchAlgorithmException nsae) {
            return false;
        }
    }
    
    public static void main(String args[]) {
        RouterContext ctx = (new Router()).getContext();
        ConsolePasswordManager pm = new ConsolePasswordManager(ctx);
        if (!pm.migrate())
            System.out.println("Fail 1");

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
        if (!pm.saveMD5("type3", "user3", "pw3"))
            System.out.println("Fail 6");
        if (!pm.checkMD5("type3", "user3", "pw3"))
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
}
