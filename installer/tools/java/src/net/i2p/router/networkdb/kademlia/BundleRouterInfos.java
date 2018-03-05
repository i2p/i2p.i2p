package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import gnu.getopt.Getopt;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.transport.BadCountries;
import net.i2p.router.transport.GeoIP;
import net.i2p.util.FileUtil;

/**
 *  Copy a random selection of 'count' router infos from configDir/netDb
 *  to 'toDir'. Skip your own router info, and old, hidden, unreachable, and
 *  introduced routers, and those from bad countries.
 *
 *  Used in the build process.
 *
 *  @since 0.9.15
 *
 */
public class BundleRouterInfos {

    /**
     *  Usage: PersistentDataStore -i configDir -o toDir -c count
     *
     *  Copy a random selection of 'count' router infos from configDir/netDb
     *  to 'toDir'. Skip your own router info, and old, hidden, unreachable, and
     *  introduced routers, and those from bad countries.
     *
     *  @since 0.9.15
     */
    public static void main(String[] args) {
        Getopt g = new Getopt("PersistentDataStore", args, "i:o:c:");
        String in = System.getProperty("user.home") + "/.i2p";
        String out = "netDb";
        int count = 200;
        boolean error = false;
        int c;
        while ((c = g.getopt()) != -1) {
          switch (c) {
            case 'i':
                in = g.getOptarg();
                break;

            case 'o':
                out = g.getOptarg();
                break;

            case 'c':
                String scount = g.getOptarg();
                try {
                    count = Integer.parseInt(scount);
                } catch (NumberFormatException nfe) {
                    error = true;
                }
                break;

            case '?':
            case ':':
            default:
                error = true;
          }
        }
        if (error) {
            usage();
            System.exit(1);
        }

        Properties props = new Properties();
        props.setProperty(GeoIP.PROP_GEOIP_DIR, System.getProperty("user.dir") + "/installer/resources");
        GeoIP geoIP = new GeoIP(new I2PAppContext(props));

        File confDir = new File(in);
        File dbDir = new File(confDir, "netDb");
        if (!dbDir.exists()) {
            System.out.println("NetDB directory " + dbDir + " does not exist");
            System.exit(1);
        }
        File myFile = new File(confDir, "router.info");
        File toDir = new File(out);
        toDir.mkdirs();
        InputStream fis = null;
        Hash me = null;
        try {
            fis = new BufferedInputStream(new FileInputStream(myFile));
            RouterInfo ri = new RouterInfo();
            ri.readBytes(fis, true);  // true = verify sig on read
            me = ri.getIdentity().getHash();
        } catch (IOException e) {
            //System.out.println("Can't determine our identity");
        } catch (DataFormatException e) {
            //System.out.println("Can't determine our identity");
        } finally {
            if (fis != null) try { fis.close(); } catch (IOException ioe) {}
        }

        int routerCount = 0;
        List<File> toRead = new ArrayList<File>(2048);
        for (int j = 0; j < Base64.ALPHABET_I2P.length(); j++) {
            File subdir = new File(dbDir, PersistentDataStore.DIR_PREFIX + Base64.ALPHABET_I2P.charAt(j));
            File[] files = subdir.listFiles(PersistentDataStore.RI_FILTER);
            if (files == null)
                continue;
            routerCount += files.length;
            for (int i = 0; i < files.length; i++) {
                toRead.add(files[i]);
            }
        }
        if (toRead.isEmpty()) {
            System.out.println("No files to copy in " + dbDir);
            System.exit(1);
        }
        Collections.shuffle(toRead);
        int copied = 0;
        long tooOld = System.currentTimeMillis() - 7*24*60*60*1000L;
        Map<String, String> ipMap = new HashMap<String, String>(count);
        for (File file : toRead) {
            if (copied >= count)
                break;
            Hash key = PersistentDataStore.getRouterInfoHash(file.getName());
            if (key == null) {
                System.out.println("Skipping bad " + file);
                continue;
            }
            if (key.equals(me)) {
                System.out.println("Skipping my RI");
                continue;
            }
            fis = null;
            try {
                fis = new BufferedInputStream(new FileInputStream(file));
                RouterInfo ri = new RouterInfo();
                ri.readBytes(fis, true);  // true = verify sig on read
                try { fis.close(); } catch (IOException ioe) {}
                fis = null;
                if (ri.getPublished() < tooOld) {
                    System.out.println("Skipping too old " + key);
                    continue;
                }
                if (ri.getCapabilities().contains("U")) {
                    System.out.println("Skipping unreachable " + key);
                    continue;
                }
                if (ri.getCapabilities().contains("K")) {
                    System.out.println("Skipping slow " + key);
                    continue;
                }
                Collection<RouterAddress> addrs = ri.getAddresses();
                if (addrs.isEmpty()) {
                    System.out.println("Skipping hidden " + key);
                    continue;
                }
                boolean hasIntro = false;
                boolean hasIPv4 = false;
                boolean dupIP = false;
                for (RouterAddress addr : addrs) {
                    if ("SSU".equals(addr.getTransportStyle()) && addr.getOption("ihost0") != null) {
                        hasIntro = true;
                        break;
                    }
                    String host = addr.getHost();
                    if (host != null && host.contains(".")) {
                        hasIPv4 = true;
                        geoIP.add(host);
                        String old = ipMap.put(host, file.getName());
                        if (old != null && !old.equals(file.getName())) {
                            dupIP = true;
                            break;
                        }
                    }
                }
                if (dupIP) {
                    System.out.println("Skipping dup IP " + key);
                    continue;
                }
                if (hasIntro) {
                    System.out.println("Skipping introduced " + key);
                    continue;
                }
                if (!hasIPv4) {
                    System.out.println("Skipping IPv6-only " + key);
                    continue;
                }
                File toFile = new File(toDir, file.getName());
                // We could call ri.write() to avoid simultaneous change by the router
                boolean ok = FileUtil.copy(file, toFile, true, true);
                if (ok)
                    copied++;
                else
                    System.out.println("Failed copy of " + file + " to " + toDir);
            } catch (IOException e) {
                System.out.println("Skipping bad " + file);
            } catch (DataFormatException e) {
                System.out.println("Skipping bad " + file);
            } finally {
                if (fis != null) try { fis.close(); } catch (IOException ioe) {}
            }
        }
        if (copied > 0) {
            // now do all the geoip lookups, and delete any bad countries
            geoIP.blockingLookup();
            for (Map.Entry<String, String> e : ipMap.entrySet()) {
                String co = geoIP.get(e.getKey());
                if (co != null) {
                    if (BadCountries.contains(co)) {
                        String name = e.getValue();
                        File toFile = new File(toDir, name);
                        if (toFile.delete()) {
                            String full = geoIP.fullName(co);
                            if (full == null)
                                full = co;
                            System.out.println("Skipping " + full + ": " + name);
                            copied--;
                        }
                    }
                }
            }
        }
        if (copied > 0) {
            System.out.println("Copied " + copied + " router info files to " + toDir);
        } else {
            System.out.println("Failed to copy any files to " + toDir);
            System.exit(1);
        }
    }

    private static void usage() {
        System.err.println("Usage: PersistentDataStore [-i $HOME/.i2p] [-o netDb/] [-c 200]");
    }
}
