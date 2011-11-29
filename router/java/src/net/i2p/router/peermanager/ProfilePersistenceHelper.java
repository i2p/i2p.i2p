package net.i2p.router.peermanager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFileOutputStream;

/**
 *  Write profiles to disk at shutdown,
 *  read at startup.
 *  The files are gzip compressed, we previously stored them
 *  with a ".dat" extension instead of ".txt.gz", so it wasn't apparent.
 *  Now migrated to a ".txt.gz" extension.
 */
class ProfilePersistenceHelper {
    private final Log _log;
    private final RouterContext _context;
    
    public final static String PROP_PEER_PROFILE_DIR = "router.profileDir";
    public final static String DEFAULT_PEER_PROFILE_DIR = "peerProfiles";
    private final static String NL = System.getProperty("line.separator");
    private static final String PREFIX = "profile-";
    private static final String SUFFIX = ".txt.gz";
    private static final String UNCOMPRESSED_SUFFIX = ".txt";
    private static final String OLD_SUFFIX = ".dat";
    
    /**
     * If we haven't been able to get a message through to the peer in 3 days,
     * drop the profile.  They may reappear, but if they do, their config may
     * have changed (etc).
     *
     */
    public static final long EXPIRE_AGE = 3*24*60*60*1000;
    
    private File _profileDir = null;
    private Hash _us;
    
    public ProfilePersistenceHelper(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(ProfilePersistenceHelper.class);
        File profileDir = getProfileDir();
        _us = null;
        if (!profileDir.exists()) {
            profileDir.mkdirs();
            _log.info("Profile directory " + profileDir.getAbsolutePath() + " created");
        }
    }
    
    public void setUs(Hash routerIdentHash) { _us = routerIdentHash; }
    
    /** write out the data from the profile to the stream */
    public void writeProfile(PeerProfile profile) {
        if (isExpired(profile.getLastSendSuccessful()))
            return;
        
        File f = pickFile(profile);
        long before = _context.clock().now();
        OutputStream fos = null;
        try {
            fos = new BufferedOutputStream(new GZIPOutputStream(new SecureFileOutputStream(f)));
            writeProfile(profile, fos);
        } catch (IOException ioe) {
            _log.error("Error writing profile to " + f);
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
        }
        long delay = _context.clock().now() - before;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Writing the profile to " + f.getName() + " took " + delay + "ms");
    }

    /** write out the data from the profile to the stream */
    public void writeProfile(PeerProfile profile, OutputStream out) throws IOException {
        String groups = null;
        if (_context.profileOrganizer().isFailing(profile.getPeer())) {
            groups = "Failing";
        } else if (!_context.profileOrganizer().isHighCapacity(profile.getPeer())) {
            groups = "Standard";
        } else {
            if (_context.profileOrganizer().isFast(profile.getPeer()))
                groups = "Fast, High Capacity";
            else
                groups = "High Capacity";
            
            if (_context.profileOrganizer().isWellIntegrated(profile.getPeer()))
                groups = groups + ", Integrated";
        }
        
        StringBuilder buf = new StringBuilder(512);
        buf.append("########################################################################").append(NL);
        buf.append("# Profile for peer ").append(profile.getPeer().toBase64()).append(NL);
        if (_us != null)
            buf.append("# as calculated by ").append(_us.toBase64()).append(NL);
        buf.append("#").append(NL);
        buf.append("# Speed: ").append(profile.getSpeedValue()).append(NL);
        buf.append("# Capacity: ").append(profile.getCapacityValue()).append(NL);
        buf.append("# Integration: ").append(profile.getIntegrationValue()).append(NL);
        buf.append("# Groups: ").append(groups).append(NL);
        buf.append("#").append(NL);
        buf.append("########################################################################").append(NL);
        buf.append("##").append(NL);
        add(buf, "speedBonus", profile.getSpeedBonus(), "Manual adjustment to the speed score");
        add(buf, "capacityBonus", profile.getCapacityBonus(), "Manual adjustment to the capacity score");
        add(buf, "integrationBonus", profile.getIntegrationBonus(), "Manual adjustment to the integration score");
        addDate(buf, "firstHeardAbout", profile.getFirstHeardAbout(), "When did we first get a reference to this peer?");
        addDate(buf, "lastHeardAbout", profile.getLastHeardAbout(), "When did we last get a reference to this peer?");
        addDate(buf, "lastHeardFrom", profile.getLastHeardFrom(), "When did we last get a message from the peer?");
        addDate(buf, "lastSentToSuccessfully", profile.getLastSendSuccessful(), "When did we last send the peer a message successfully?");
        addDate(buf, "lastFailedSend", profile.getLastSendFailed(), "When did we last fail to send a message to the peer?");
        add(buf, "tunnelTestTimeAverage", profile.getTunnelTestTimeAverage(), "Moving average as to how fast the peer replies");
        add(buf, "tunnelPeakThroughput", profile.getPeakThroughputKBps(), "KBytes/sec");
        add(buf, "tunnelPeakTunnelThroughput", profile.getPeakTunnelThroughputKBps(), "KBytes/sec");
        add(buf, "tunnelPeakTunnel1mThroughput", profile.getPeakTunnel1mThroughputKBps(), "KBytes/sec");
        buf.append(NL);
        
        out.write(buf.toString().getBytes());
        
        if (profile.getIsExpanded()) {
            // only write out expanded data if, uh, we've got it
            profile.getTunnelHistory().store(out);
            //profile.getReceiveSize().store(out, "receiveSize");
            //profile.getSendSuccessSize().store(out, "sendSuccessSize");
            profile.getTunnelCreateResponseTime().store(out, "tunnelCreateResponseTime");
            profile.getTunnelTestResponseTime().store(out, "tunnelTestResponseTime");
        }

        if (profile.getIsExpandedDB()) {
            profile.getDBHistory().store(out);
            profile.getDbIntroduction().store(out, "dbIntroduction");
            profile.getDbResponseTime().store(out, "dbResponseTime");
        }
    }
    
    /** @since 0.8.5 */
    private static void addDate(StringBuilder buf, String name, long val, String description) {
        String when = val > 0 ? (new Date(val)).toString() : "Never";
        add(buf, name, val, description + ' ' + when);
    }
    
    /** @since 0.8.5 */
    private static void add(StringBuilder buf, String name, long val, String description) {
        buf.append("# ").append(name).append(NL).append("# ").append(description).append(NL);
        buf.append(name).append('=').append(val).append(NL).append(NL);
    }
    
    /** @since 0.8.5 */
    private static void add(StringBuilder buf, String name, double val, String description) {
        buf.append("# ").append(name).append(NL).append("# ").append(description).append(NL);
        buf.append(name).append('=').append(val).append(NL).append(NL);
    }
    
    public Set<PeerProfile> readProfiles() {
        long start = _context.clock().now();
        Set<File> files = selectFiles();
        Set<PeerProfile> profiles = new HashSet(files.size());
        for (File f :  files) {
            PeerProfile profile = readProfile(f);
            if (profile != null)
                profiles.add(profile);
        }
        long duration = _context.clock().now() - start;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Loading " + profiles.size() + " took " + duration + "ms");
        return profiles;
    }
    
    private Set<File> selectFiles() {
        File files[] = getProfileDir().listFiles(new FilenameFilter() {
            public boolean accept(File dir, String filename) {
                return (filename.startsWith(PREFIX) &&
                        (filename.endsWith(SUFFIX) || filename.endsWith(OLD_SUFFIX) || filename.endsWith(UNCOMPRESSED_SUFFIX)));
            }
        });
        Set rv = new HashSet(files.length);
        for (int i = 0; i < files.length; i++)
            rv.add(files[i]);
        return rv;
    }
    
    private boolean isExpired(long lastSentToSuccessfully) {
        long timeSince = _context.clock().now() - lastSentToSuccessfully;
        return (timeSince > EXPIRE_AGE);
    }
    
    public PeerProfile readProfile(File file) {
        Hash peer = getHash(file.getName());
        try {
            if (peer == null) {
                _log.error("The file " + file.getName() + " is not a valid hash");
                return null;
            }
            PeerProfile profile = new PeerProfile(_context, peer);
            Properties props = new Properties();
            
            loadProps(props, file);
            
            long lastSentToSuccessfully = getLong(props, "lastSentToSuccessfully");
            if (isExpired(lastSentToSuccessfully)) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Dropping old profile " + file.getName() + 
                              ", since we haven't heard from them in a long time");
                file.delete();
                return null;
            } else if (file.getName().endsWith(OLD_SUFFIX)) {
                // migrate to new file name, ignore failure
                String newName = file.getAbsolutePath();
                newName = newName.substring(0, newName.length() - OLD_SUFFIX.length()) + SUFFIX;
                boolean success = file.renameTo(new File(newName));
                if (!success)
                    // new file exists and on Windows?
                    file.delete();
            }
            
            profile.setCapacityBonus(getLong(props, "capacityBonus"));
            profile.setIntegrationBonus(getLong(props, "integrationBonus"));
            profile.setSpeedBonus(getLong(props, "speedBonus"));
            
            profile.setLastHeardAbout(getLong(props, "lastHeardAbout"));
            profile.setFirstHeardAbout(getLong(props, "firstHeardAbout"));
            profile.setLastSendSuccessful(getLong(props, "lastSentToSuccessfully"));
            profile.setLastSendFailed(getLong(props, "lastFailedSend"));
            profile.setLastHeardFrom(getLong(props, "lastHeardFrom"));
            profile.setTunnelTestTimeAverage(getDouble(props, "tunnelTestTimeAverage"));
            profile.setPeakThroughputKBps(getDouble(props, "tunnelPeakThroughput"));
            profile.setPeakTunnelThroughputKBps(getDouble(props, "tunnelPeakTunnelThroughput"));
            profile.setPeakTunnel1mThroughputKBps(getDouble(props, "tunnelPeakTunnel1mThroughput"));
            
            profile.getTunnelHistory().load(props);

            // In the interest of keeping the in-memory profiles small,
            // don't load the DB info at all unless there is something interesting there
            // (i.e. floodfills)
            // It seems like we do one or two lookups as a part of handshaking?
            // Not sure, to be researched.
            if (getLong(props, "dbHistory.successfulLookups") > 1 ||
                getLong(props, "dbHistory.failedlLokups") > 1) {
                profile.expandDBProfile();
                profile.getDBHistory().load(props);
                profile.getDbIntroduction().load(props, "dbIntroduction", true);
                profile.getDbResponseTime().load(props, "dbResponseTime", true);
            }

            //profile.getReceiveSize().load(props, "receiveSize", true);
            //profile.getSendSuccessSize().load(props, "sendSuccessSize", true);
            profile.getTunnelCreateResponseTime().load(props, "tunnelCreateResponseTime", true);
            profile.getTunnelTestResponseTime().load(props, "tunnelTestResponseTime", true);
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Loaded the profile for " + peer.toBase64() + " from " + file.getName());
            
            return profile;
        } catch (Exception e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error loading properties from " + file.getAbsolutePath(), e);
            file.delete();
            return null;
        }
    }
    
    private final static long getLong(Properties props, String key) {
        String val = props.getProperty(key);
        if (val != null) {
            try {
                return Long.parseLong(val);
            } catch (NumberFormatException nfe) {
                return 0;
            }
        }
        return 0;
    }

    private final static double getDouble(Properties props, String key) {
        String val = props.getProperty(key);
        if (val != null) {
            try {
                return Double.parseDouble(val);
            } catch (NumberFormatException nfe) {
                return 0.0;
            }
        }
        return 0.0;
    }
    
    private void loadProps(Properties props, File file) throws IOException {
        InputStream fin = null;
        try {
            fin = new BufferedInputStream(new FileInputStream(file), 1);
            fin.mark(1);
            int c = fin.read(); 
            fin.reset();
            if (c == '#') {
                // uncompressed
                if (_log.shouldLog(Log.INFO))
                    _log.info("Loading uncompressed profile data from " + file.getName());
                DataHelper.loadProps(props, fin);
            } else {
                // compressed (or corrupt...)
                if (_log.shouldLog(Log.INFO))
                    _log.info("Loading compressed profile data from " + file.getName());
                DataHelper.loadProps(props, new GZIPInputStream(fin));
            }
        } finally {
            try {
                if (fin != null) fin.close();
            } catch (IOException e) {}
        }
    }

    private Hash getHash(String name) {
        String key = name.substring(PREFIX.length());
        key = key.substring(0, 44);
        //Hash h = new Hash();
        try {
            //h.fromBase64(key);
            byte[] b = Base64.decode(key);
            if (b == null)
                return null;
            Hash h = Hash.create(b);
            return h;
        } catch (Exception dfe) {
            _log.warn("Invalid base64 [" + key + "]", dfe);
            return null;
        }
    }
    
    private File pickFile(PeerProfile profile) {
        return new File(getProfileDir(), PREFIX + profile.getPeer().toBase64() + SUFFIX);
    }
    
    private File getProfileDir() {
        if (_profileDir == null) {
            String dir = _context.getProperty(PROP_PEER_PROFILE_DIR, DEFAULT_PEER_PROFILE_DIR);
            _profileDir = new SecureDirectory(_context.getRouterDir(), dir);
        }
        return _profileDir;
    }
    
    /** generate 1000 profiles */
/****
    public static void main(String args[]) {
        System.out.println("Generating 1000 profiles");
        File dir = new File("profiles");
        dir.mkdirs();
        byte data[] = new byte[32];
        java.util.Random rnd = new java.util.Random();
        for (int i = 0; i < 1000; i++) {
            rnd.nextBytes(data);
            Hash peer = new Hash(data);
            try {
                File f = new File(dir, PREFIX + peer.toBase64() + SUFFIX);
                f.createNewFile();
                System.out.println("Created " + peer.toBase64());
            } catch (IOException ioe) {}
        }
        System.out.println("1000 peers created in " + dir.getAbsolutePath());
    }
****/
}
