package net.i2p.router.tunnelmanager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Properties;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.TunnelId;
import net.i2p.router.ClientTunnelSettings;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.util.Log;

/**
 * Handle all of the load / store of the tunnel pool (including any contained 
 * client tunnel pools)
 *
 */
class TunnelPoolPersistenceHelper {
    private Log _log;
    private RouterContext _context;
    
    public final static String PARAM_TUNNEL_POOL_FILE = "router.tunnelPoolFile";
    public final static String DEFAULT_TUNNEL_POOL_FILE = "tunnelPool.dat";
    
    public TunnelPoolPersistenceHelper(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(TunnelPoolPersistenceHelper.class);
    }
    
    public void writePool(TunnelPool pool) {
        File f = getTunnelPoolFile();
        writePool(pool, f);
    }
    public void writePool(TunnelPool pool, File f) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f);
            DataHelper.writeLong(fos, 2, pool.getFreeTunnelCount());
            for (Iterator iter = pool.getFreeTunnels().iterator(); iter.hasNext(); ) {
                TunnelId id = (TunnelId)iter.next();
                TunnelInfo info = pool.getFreeTunnel(id);
                if (info != null)
                    info.writeBytes(fos);
            }
            DataHelper.writeLong(fos, 2, pool.getOutboundTunnelCount());
            for (Iterator iter = pool.getOutboundTunnels().iterator(); iter.hasNext(); ) {
                TunnelId id = (TunnelId)iter.next();
                TunnelInfo info = pool.getOutboundTunnel(id);
                if (info != null)
                    info.writeBytes(fos);
            }
            DataHelper.writeLong(fos, 2, pool.getParticipatingTunnels().size());
            for (Iterator iter = pool.getParticipatingTunnels().iterator(); iter.hasNext(); ) {
                TunnelId id = (TunnelId)iter.next();
                TunnelInfo info = pool.getParticipatingTunnel(id);
                if (info != null)
                    info.writeBytes(fos);
            }
            DataHelper.writeLong(fos, 2, pool.getPendingTunnels().size());
            for (Iterator iter = pool.getPendingTunnels().iterator(); iter.hasNext(); ) {
                TunnelId id = (TunnelId)iter.next();
                TunnelInfo info = pool.getPendingTunnel(id);
                if (info != null)
                    info.writeBytes(fos);
            }
            DataHelper.writeLong(fos, 2, pool.getClientPools().size());
            for (Iterator iter = pool.getClientPools().iterator(); iter.hasNext(); ) {
                Destination dest = (Destination)iter.next();
                ClientTunnelPool cpool = (ClientTunnelPool)pool.getClientPool(dest);
                writeClientPool(fos, cpool);
            }
            fos.flush();
        } catch (IOException ioe) {
            _log.error("Error writing tunnel pool at " + f.getName(), ioe);
        } catch (DataFormatException dfe) {
            _log.error("Error formatting tunnels at " + f.getName(), dfe);
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
            _log.debug("Tunnel pool state written to " + f.getName());
        }
    }
    
    private void writeClientPool(FileOutputStream fos, ClientTunnelPool pool) throws IOException, DataFormatException {
        pool.getDestination().writeBytes(fos);
        Properties props = new Properties();
        pool.getClientSettings().writeToProperties(props);
        DataHelper.writeProperties(fos, props);
        DataHelper.writeLong(fos, 2, pool.getInboundTunnelIds().size());
        for (Iterator iter = pool.getInboundTunnelIds().iterator(); iter.hasNext(); ) {
            TunnelId id = (TunnelId)iter.next();
            TunnelInfo info = pool.getInboundTunnel(id);
            if (info != null)
                info.writeBytes(fos);
        }
        DataHelper.writeLong(fos, 2, pool.getInactiveInboundTunnelIds().size());
        for (Iterator iter = pool.getInactiveInboundTunnelIds().iterator(); iter.hasNext(); ) {
            TunnelId id = (TunnelId)iter.next();
            TunnelInfo info = pool.getInactiveInboundTunnel(id);
            if (info != null)
                info.writeBytes(fos);
        }
    }
    
    /**
     * Load up the tunnels from disk, adding as appropriate to the TunnelPool
     */
    public void loadPool(TunnelPool pool) {
        File f = getTunnelPoolFile();
        loadPool(pool, f);
    }
    
    public void loadPool(TunnelPool pool, File f) {
        if (!f.exists()) return;
        FileInputStream fin = null;
        try {
            fin = new FileInputStream(f);
            int numFree = (int)DataHelper.readLong(fin, 2);
            for (int i = 0; i < numFree; i++) {
                TunnelInfo info = new TunnelInfo(_context);
                info.readBytes(fin);
                pool.addFreeTunnel(info);
            }
            int numOut = (int)DataHelper.readLong(fin, 2);
            for (int i = 0; i < numOut; i++) {
                TunnelInfo info = new TunnelInfo(_context);
                info.readBytes(fin);
                pool.addOutboundTunnel(info);
            }
            int numParticipating = (int)DataHelper.readLong(fin, 2);
            for (int i = 0; i < numParticipating; i++) {
                TunnelInfo info = new TunnelInfo(_context);
                info.readBytes(fin);
                pool.addParticipatingTunnel(info);
            }
            int numPending = (int)DataHelper.readLong(fin, 2);
            for (int i = 0; i < numPending; i++) {
                TunnelInfo info = new TunnelInfo(_context);
                info.readBytes(fin);
                pool.addPendingTunnel(info);
            }
            int numClients = (int)DataHelper.readLong(fin, 2);
            for (int i = 0; i < numClients; i++) {
                readClientPool(fin, pool);
            }
        } catch (IOException ioe) {
            _log.error("Error reading tunnel pool from " + f.getName(), ioe);
        } catch (DataFormatException dfe) {
            _log.error("Error formatting tunnels from " + f.getName(), dfe);
        } finally {
            if (fin != null) try { fin.close(); } catch (IOException ioe) {}
            _log.debug("Tunnel pool state written to " + f.getName());
        }
    }
    

    private void readClientPool(FileInputStream fin, TunnelPool pool) throws IOException, DataFormatException {
        Destination dest = new Destination();
        dest.readBytes(fin);
        ClientTunnelSettings settings = new ClientTunnelSettings();
        Properties props = DataHelper.readProperties(fin);
        settings.readFromProperties(props);
        HashSet activeTunnels = new HashSet();
        int numActiveTunnels = (int)DataHelper.readLong(fin, 2);
        for (int i = 0; i < numActiveTunnels; i++) {
            TunnelInfo info = new TunnelInfo(_context);
            info.readBytes(fin);
            activeTunnels.add(info);
        }
        HashSet inactiveTunnels = new HashSet();
        int numInactiveTunnels = (int)DataHelper.readLong(fin, 2);
        for (int i = 0; i < numInactiveTunnels; i++) {
            TunnelInfo info = new TunnelInfo(_context);
            info.readBytes(fin);
            inactiveTunnels.add(info);
        }
	
        ClientTunnelPool cpool = new ClientTunnelPool(_context, dest, settings, pool);
        cpool.setActiveTunnels(activeTunnels);
        cpool.setInactiveTunnels(inactiveTunnels);
        pool.addClientPool(cpool);
        cpool.startPool();
    }

    
    /**
     * Retrieve the file the pool should be persisted in
     *
     */
    private File getTunnelPoolFile() {
        String filename = null;

        String str = _context.router().getConfigSetting(PARAM_TUNNEL_POOL_FILE);
        if ( (str != null) && (str.trim().length() > 0) )
            filename = str;
        else
            filename = DEFAULT_TUNNEL_POOL_FILE;

        return new File(filename);
    }
}
