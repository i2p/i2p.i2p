package net.i2p.router.networkdb.reseed;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;

/**
 *  Copy a random selection of 'count' router infos from configDir/netDb
 *  to 'toDir'. Skip your own router info, and old, hidden, unreachable, and
 *  introduced routers, and those from bad countries.
 *
 *  Much easier than the one in installer/tools since we have a running router.
 *
 *  Caller must delete file when done.
 *
 *  @since 0.9.19 modified from BundleRouterInfos in installer/tools,
 *                moved from routerconsole to net.i2p.router.networkdb.reseed in 0.9.34
 *
 */
public class ReseedBundler {

    private final RouterContext _context;
    private final static String ROUTERINFO_PREFIX = "routerInfo-";
    private final static String ROUTERINFO_SUFFIX = ".dat";
    private static final int MINIMUM = 50;

    public ReseedBundler(RouterContext ctx) {
        _context = ctx;
    }


    /**
     *  Create a zip file with
     *  a random selection of 'count' router infos from configDir/netDb
     *  to 'toDir'. Skip your own router info, and old, hidden, unreachable, and
     *  introduced routers, and those from bad countries.
     *
     *  The file will be in the temp directory. Caller must move or delete.
     */
    public File createZip(int count) throws IOException {
        Hash me = _context.routerHash();
        int routerCount = 0;
        int copied = 0;
        long tooOld = System.currentTimeMillis() - 7*24*60*60*1000L;
        List<RouterInfo> infos = new ArrayList<RouterInfo>(_context.netDb().getRouters());
        // IP to router hash
        Map<String, Hash> ipMap = new HashMap<String, Hash>(count);
        List<RouterInfo> toWrite = new ArrayList<RouterInfo>(count);
        Collections.shuffle(infos);
        for (RouterInfo ri : infos) {
            if (copied >= count)
                break;
            Hash key = ri.getIdentity().calculateHash();
            if (key.equals(me)) {
                continue;
            }
            if (ri.getPublished() < tooOld)
                continue;
            if (ri.getCapabilities().contains("U"))
                continue;
            if (ri.getCapabilities().contains("K"))
                continue;
            Collection<RouterAddress> addrs = ri.getAddresses();
            if (addrs.isEmpty())
                continue;
            
            String name = getRouterInfoName(key);
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
                    Hash old = ipMap.put(host, key);
                    if (old != null && !old.equals(key)) {
                        dupIP = true;
                        break;
                    }
                }
            }
            if (dupIP)
                continue;
            if (hasIntro)
                continue;
            if (!hasIPv4)
                continue;
            if (_context.commSystem().isInBadCountry(ri))
                continue;

            toWrite.add(ri);
            copied++;
        }

        if (toWrite.isEmpty())
            throw new IOException("No router infos to include. Reseed yourself first.");
        if (toWrite.size() < Math.min(count, MINIMUM))
            throw new IOException("Not enough router infos to include, wanted " + count +
                                  " but only found " + toWrite.size() + ". Please try again later.");

        File rv = new File(_context.getTempDir(), "genreseed-" + _context.random().nextInt() + ".zip");
        ZipOutputStream zip = null;
        try {
            zip = new ZipOutputStream(new FileOutputStream(rv) );
            for (RouterInfo ri : toWrite) {
                String name = getRouterInfoName(ri.getIdentity().calculateHash());
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(ri.getPublished());
                zip.putNextEntry(entry);
                ri.writeBytes(zip);
                zip.closeEntry();
            }
        } catch (DataFormatException dfe) {
            rv.delete();
            IOException ioe = new IOException(dfe.getMessage());
            ioe.initCause(dfe);
            throw ioe;
        } catch (IOException ioe) {
            rv.delete();
            throw ioe;
        } finally {
            if ( zip != null) {
                try {
                    zip.finish();
                    zip.close();
                } catch (IOException ioe) {
                    rv.delete();
                    throw ioe;
                }
            }
        }
        return rv;
    }

    /**
     *  Copied/modded from PersistentDataStore
     */
    private static String getRouterInfoName(Hash hash) {
        String b64 = hash.toBase64();
        return ROUTERINFO_PREFIX + b64 + ROUTERINFO_SUFFIX;
    }
}
