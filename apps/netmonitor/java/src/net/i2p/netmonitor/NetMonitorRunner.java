package net.i2p.netmonitor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

import net.i2p.data.DataFormatException;
import net.i2p.data.RouterInfo;
import net.i2p.util.Clock;
import net.i2p.util.Log;

/**
 * Active process that drives the monitoring by periodically rading the 
 * netDb dir, pumping the router data throug the data harvester, updating 
 * the state, and coordinating the export.
 *
 */
class NetMonitorRunner implements Runnable {
    private static final Log _log = new Log(NetMonitorRunner.class);
    private NetMonitor _monitor;
    /** 
     * @param monitor who do we give our data to? 
     */
    public NetMonitorRunner(NetMonitor monitor) {
        _monitor = monitor;
    }
    
    public void run() {
        runImport();
        long now = Clock.getInstance().now();
        long nextHarvest = now;
        long nextExport = now + _monitor.getExportDelay() * 1000;
        while (_monitor.isRunning()) {
            now = Clock.getInstance().now();
            _monitor.coallesceData();
            if (now >= nextHarvest) {
                runHarvest();
                nextHarvest = now + _monitor.getHarvestDelay() * 1000;
            }
            if (now >= nextExport) {
                runExport();
                nextExport = now + _monitor.getExportDelay() * 1000;
            }
            pauseHarvesting();
        }
    }
    
    private void runHarvest() {
        try {
            List routers = getRouters();
            DataHarvester.getInstance().harvestData(_monitor, routers);
        } catch (Throwable t) {
            _log.error("Unhandled exception harvesting the data", t);
        }
    }
    
    /**
     * Fetch all of the available RouterInfo structures
     *
     */
    private List getRouters() {
        if (_monitor.getNetDbURL() != null)
            return fetchRouters(_monitor.getNetDbURL());
        
        File routers[] = listRouters();
        List rv = new ArrayList(64);
        if (routers != null) {
            for (int i = 0; i < routers.length; i++) {
                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(routers[i]);
                    RouterInfo ri = new RouterInfo();
                    ri.readBytes(fis);
                    rv.add(ri);
                } catch (DataFormatException dfe) {
                    _log.warn("Unable to parse the routerInfo from " + routers[i].getAbsolutePath(), dfe);
                } catch (IOException ioe) {
                    _log.warn("Unable to read the routerInfo from " + routers[i].getAbsolutePath(), ioe);
                } finally {
                    if (fis != null) try { fis.close(); } catch (IOException ioe) {}
                }
            }
        }
        return rv;
    }
    
    
    private List fetchRouters(String seedURL) {
        List rv = new ArrayList();
        try {
            URL dir = new URL(seedURL);
            String content = new String(readURL(dir));
            Set urls = new HashSet();
            int cur = 0;
            while (true) {
                int start = content.indexOf("href=\"routerInfo-", cur);
                if (start < 0)
                    break;

                int end = content.indexOf(".dat\">", start);
                String name = content.substring(start+"href=\"routerInfo-".length(), end);
                urls.add(name);
                cur = end + 1;
            }

            for (Iterator iter = urls.iterator(); iter.hasNext(); ) {
                rv.add(fetchSeed((String)iter.next()));
            }
        } catch (Throwable t) {
            _log.error("Error fetching routers from " + seedURL, t);
        }
        return rv;
    }
    
    private RouterInfo fetchSeed(String peer) throws Exception {
        URL url = new URL("http://i2p.net/i2pdb/routerInfo-" + peer + ".dat");
        if (_log.shouldLog(Log.INFO))
            _log.info("Fetching seed from " + url.toExternalForm());

        byte data[] = readURL(url);
        RouterInfo info = new RouterInfo();
        try {
            info.fromByteArray(data);
            return info;
        } catch (DataFormatException dfe) {
            _log.error("Router data at " + url.toExternalForm() + " was corrupt", dfe);
            return null;
        }
    }
    
    private byte[] readURL(URL url) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
        URLConnection con = url.openConnection();
        InputStream in = con.getInputStream();
        byte buf[] = new byte[1024];
        while (true) {
            int read = in.read(buf);
            if (read < 0)
                break;
            baos.write(buf, 0, read);
        }
        in.close();
        return baos.toByteArray();
    }
    
    /**
     * dump the data to the filesystem
     */
    private void runExport() {
        _log.info("Export");
        List peers = _monitor.getPeers();
        File exportDir = new File(_monitor.getExportDir());
        if (!exportDir.exists())
            exportDir.mkdirs();
        for (int i = 0; i < peers.size(); i++) {
            String peerName = (String)peers.get(i);
            PeerSummary summary = (PeerSummary)_monitor.getSummary(peerName);
            FileOutputStream fos = null;
            try {
                File summaryFile = new File(exportDir, peerName + ".txt");
                fos = new FileOutputStream(summaryFile);
                PeerSummaryWriter.getInstance().write(summary, fos);
                _log.debug("Peer summary written to " + summaryFile.getAbsolutePath());
            } catch (IOException ioe) {
                _log.error("Error exporting the peer summary for " + peerName, ioe);
            } catch (Throwable t) {
                _log.error("Unhandled exception exporting the data", t);
            } finally {
                if (fos != null) try { fos.close(); } catch (IOException ioe) {}
            }
        }
    }
    
    /**
     * Read in all the peer summaries we had previously exported, overwriting any
     * existing ones in memory
     *
     */
    private void runImport() {
        _monitor.importData();
    }
    
    /** 
     * Find all of the routers to load 
     *
     * @return list of File objects pointing at the routers around
     */
    private File[] listRouters() { 
        if (_monitor.getExplicitRouters() != null) {
            return listRoutersExplicit();
        } else {
            File dbDir = new File(_monitor.getNetDbDir());
            File files[] = dbDir.listFiles(new FilenameFilter() {
                public boolean accept(File f, String name) {
                    return name.startsWith("routerInfo-");
                }
            });
            return files;
        }
    }

    /**
     * Get a list of router files that were explicitly specified by the netMonitor
     *
     */
    private File[] listRoutersExplicit() {
        StringTokenizer tok = new StringTokenizer(_monitor.getExplicitRouters().trim(), ",");
        List rv = new ArrayList();
        while (tok.hasMoreTokens()) {
            String name = tok.nextToken();
            File cur = new File(name);
            if (cur.exists())
                rv.add(cur);
        }
        File files[] = new File[rv.size()];
        for (int i = 0; i < rv.size(); i++)
            files[i] = (File)rv.get(i);
        return files;
    }
    
    /**
     * Wait the correct amount of time before harvesting again
     *
     */
    private void pauseHarvesting() {
        try {
            Thread.sleep(_monitor.getHarvestDelay());
        } catch (InterruptedException ie) {}
    }
}
