package net.i2p.netmonitor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;

import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.data.DataFormatException;
import net.i2p.data.RouterInfo;

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
        List routers = getRouters();
        DataHarvester.getInstance().harvestData(_monitor, routers);
    }
    
    /**
     * Fetch all of the available RouterInfo structures
     *
     */
    private List getRouters() {
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
        File dbDir = new File(_monitor.getNetDbDir());
        File files[] = dbDir.listFiles(new FilenameFilter() {
            public boolean accept(File f, String name) {
                return name.startsWith("routerInfo-");
            }
        });
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