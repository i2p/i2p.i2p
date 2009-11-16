package i2p.bote.web;

import i2p.bote.EmailIdentity;
import i2p.bote.I2PBote;
import i2p.bote.Identities;
import i2p.bote.Util;
import i2p.bote.folder.EmailFolder;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import net.i2p.util.Log;

/**
 * Implements JSP functions.
 *
 * @author HungryHobo@mail.i2p
 */
public class JSPHelper {
    private static final Log log = new Log(JSPHelper.class);
    private static final int MAX_THREADS = 10;
    private static final int THREAD_STACK_SIZE = 64 * 1024;   // TODO find a safe low value (default in 64-bit Java 1.6 = 1MByte)
    private static final ThreadFactory MAIL_CHECK_THREAD_FACTORY = Util.createThreadFactory("CheckMail", THREAD_STACK_SIZE);
    private static ExecutorService EXECUTOR;

    private JSPHelper() {
        throw new UnsupportedOperationException();
    }
    
    public static Identities getIdentities() {
        return I2PBote.getInstance().getIdentities();
    }
    
    /**
     * Updates an email identity if <code>key</code> exists, or adds a new identity.
     * @param key A base64-encoded email identity key
     * @param description
     * @param publicName
     * @param emailAddress
     * @return null if sucessful, or an error message if an error occured
     */
    public static String saveIdentity(String key, String publicName, String description, String emailAddress) {
        Identities identities = JSPHelper.getIdentities();
        EmailIdentity identity = identities.get(key);
        
        if (identity != null) {
            identity.setPublicName(publicName);
            identity.setDescription(description);
            identity.setEmailAddress(emailAddress);
        }
        else {
            identity = new EmailIdentity();
            identity.setPublicName(publicName);
            identity.setDescription(description);
            identity.setEmailAddress(emailAddress);
            identities.add(identity);
        }

        try {
            identities.save();
            return null;
        }
        catch (IOException e) {
            return e.getLocalizedMessage();
        }
    }

    /**
     * Deletes an email identity.
     * @param key A base64-encoded email identity key
     * @return null if sucessful, or an error message if an error occured
     */
    public static String deleteIdentity(String key) {
        Identities identities = JSPHelper.getIdentities();
        identities.remove(key);

        try {
            identities.save();
            return null;
        }
        catch (IOException e) {
            return e.getLocalizedMessage();
        }
    }

    public static void checkForMail() {
        if (!isCheckingForMail()) {
            EXECUTOR = Executors.newFixedThreadPool(MAX_THREADS, MAIL_CHECK_THREAD_FACTORY);
            
            I2PBote bote = I2PBote.getInstance();
            for (EmailIdentity identity: bote.getIdentities())
                EXECUTOR.submit(bote.createCheckMailTask(identity));
            
            EXECUTOR.shutdown();
            try {
                EXECUTOR.awaitTermination(1, TimeUnit.DAYS);
            }
            catch (InterruptedException e) {
                log.error("Interrupted while checking for mail.", e);
                EXECUTOR.shutdownNow();
            }
        }
    }

    public static boolean isCheckingForMail() {
        return (EXECUTOR!=null && !EXECUTOR.isTerminated());
    }
    
    public static EmailFolder getMailFolder(String folderName) {
        if ("Inbox".equals(folderName))
            return I2PBote.getInstance().getInbox();
        else
            return null;
    }
}